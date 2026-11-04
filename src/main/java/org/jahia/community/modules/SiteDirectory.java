package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.exceptions.JahiaException;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.utils.Url;
import org.slf4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The hostname-to-site resolution and the site list, shared by the servlet layer
 * ({@link CrossSitesFilter}) and the render layer ({@link CrossSitesRenderFilter}) so that both apply
 * exactly the same normalisation, localhost rule, lookup and fail-open logging.
 *
 * <p>The hostname is normalised (every trailing dot stripped, so {@code siteb.example.} is
 * {@code siteb.example}) and resolved through {@link JahiaSitesService}, whose lookup is cached and
 * compares case-insensitively. The request attribute cached by {@code ServerNameToSiteMapper} is
 * neither read nor written, so this module cannot poison what Jahia's own rewrite rules see.
 */
final class SiteDirectory {

    /** Request attribute ErrorServlet reads before it resolves the URL, so the 404 page is the hostname's site's. */
    static final String SITE_KEY_ATTRIBUTE = "siteKey";

    /** Seam over {@link JahiaSitesService#getSitenameByServerName(String)}, which throws a checked exception. */
    @FunctionalInterface
    interface ServerNameLookup {
        String siteKeyFor(String serverName) throws JahiaException;
    }

    private final Supplier<? extends Collection<String>> siteNames;
    private final ServerNameLookup serverNameLookup;
    private final Logger logger;
    private final FailureTracker siteListFailure;
    private final FailureTracker hostLookupFailure;

    SiteDirectory(Supplier<? extends Collection<String>> siteNames, ServerNameLookup serverNameLookup, Logger logger,
                  LongSupplier clockMillis) {
        this.siteNames = siteNames;
        this.serverNameLookup = serverNameLookup;
        this.logger = logger;
        this.siteListFailure = new FailureTracker("the site list", logger, clockMillis);
        this.hostLookupFailure = new FailureTracker("the hostname-to-site lookup", logger, clockMillis);
    }

    /** Wired to the real Jahia services. */
    static SiteDirectory forJahia(Logger logger) {
        return new SiteDirectory(() -> JahiaSitesService.getInstance().getSitesNames(),
                host -> JahiaSitesService.getInstance().getSitenameByServerName(host), logger, System::currentTimeMillis);
    }

    /**
     * Cache-backed in Jahia, so cheap enough per request. It can still fail while the repository is
     * starting, and a filter that threw there would take every request down with it; no list means
     * "resolve no site", which leaves the request to Jahia.
     */
    Collection<String> siteNames() {
        try {
            final Collection<String> names = siteNames.get();
            if (names == null || names.isEmpty()) {
                // Jahia always has at least systemsite, so an empty list is as unusable as null.
                siteListFailure.failed(names == null ? "returned null" : "returned no site", null);
                return Collections.emptyList();
            }
            siteListFailure.succeeded();
            return names;
        } catch (RuntimeException e) {
            siteListFailure.failed(FailureTracker.LOOKUP_FAILED, e);
            return Collections.emptyList();
        }
    }

    /** @return the site the normalised hostname maps to, or null (unmapped, localhost, empty, lookup failure) */
    String siteKeyForServerName(String serverName) {
        if (serverName.isEmpty() || Url.isLocalhost(serverName)) {
            return null;
        }
        try {
            final String key = serverNameLookup.siteKeyFor(serverName);
            hostLookupFailure.succeeded();
            if (StringUtils.isEmpty(key) && logger.isDebugEnabled()) {
                logger.debug("Hostname '{}' maps to no site, cross-site check skipped", LogValues.sanitise(serverName));
            }
            return key;
        } catch (JahiaException | RuntimeException e) {
            hostLookupFailure.failed(FailureTracker.LOOKUP_FAILED, e);
            return null;
        }
    }

    /** Strips every trailing dot: "siteb.example." and "siteb.example" are the same host. */
    static String normalizeServerName(String serverName) {
        String host = StringUtils.defaultString(serverName);
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }

    /** An existing site other than the hostname's and other than systemsite, which every site shares. */
    static boolean isForeignSite(String siteKey, Set<String> sites, String siteKeyFromHost) {
        return siteKey != null && sites.contains(siteKey) && !siteKey.equals(siteKeyFromHost)
                && !JahiaSitesService.SYSTEM_SITE_KEY.equals(siteKey);
    }

    static Set<String> toSet(Collection<String> names) {
        return names instanceof Set ? (Set<String>) names : new HashSet<>(names);
    }

    boolean isSiteListFailing() {
        return siteListFailure.isFailing();
    }

    boolean isHostLookupFailing() {
        return hostLookupFailure.isFailing();
    }

    /** The request's hostname and its site, looked up at most once per request and only when needed. */
    static final class HostSite {
        private final SiteDirectory directory;
        private final HttpServletRequest request;
        private String host;
        private String siteKey;
        private boolean isResolved;

        HostSite(SiteDirectory directory, HttpServletRequest request) {
            this.directory = directory;
            this.request = request;
        }

        String host() {
            siteKey();
            return host;
        }

        String siteKey() {
            if (!isResolved) {
                host = normalizeServerName(request.getServerName());
                siteKey = directory.siteKeyForServerName(host);
                isResolved = true;
            }
            return siteKey;
        }
    }
}
