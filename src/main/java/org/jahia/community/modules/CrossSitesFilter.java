package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.utils.Url;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Answers 404 to a request whose path addresses the live content of one site while the request's
 * hostname maps to another site.
 *
 * <p>The whole control rests on answering one question the same way Jahia answers it: <em>which
 * site does this URL address?</em> If this filter and the renderer disagree, the filter is either
 * blocking content nobody asked for or waving through the cross-site access it exists to stop.
 * Deliberate choices that follow from that:
 *
 * <ul>
 *   <li>the match runs on the <em>decoded</em> path, {@code getServletPath() + getPathInfo()}.
 *       The servlet container (Tomcat) decodes and normalises both before any filter sees them;
 *       this class does no decoding of its own. The renderer resolves content from the same
 *       values, whereas matching on the raw request-target let {@code /%73ites/} slip past here
 *       while the renderer still read it as {@code /sites/}.</li>
 *   <li>the filter is registered for REQUEST <em>and</em> FORWARD. On Jahia up to 8.2.3.2 the SEO
 *       inbound rule forwards, and the forward decodes the path a second time: a double-encoded
 *       key ({@code /en/sites/%2564igitall/home.html}) reads as {@code %64igitall} on REQUEST and
 *       as a real site only on the FORWARD pass. On FORWARD the servlet path and path info are
 *       the forward target's.</li>
 *   <li>the site key is extracted the way {@code org.jahia.services.render.URLResolver} does in
 *       {@code resolveSiteKeyFromPath} (checked against jahia-impl 8.2.2.1), including the
 *       trailing-segment and template-extension forms that a single {@code substringBetween}
 *       misses, then restricted to sites that exist.</li>
 *   <li>JCR accepts the expanded-name form {@code {}name} (empty namespace) as the node
 *       {@code name}, one leading {@code {}} per path segment. The site key is therefore resolved
 *       on the path with that single marker stripped from every segment, otherwise
 *       {@code /sites/{}digitall/} would address digitall while reading as an unknown key here. The
 *       authoring test runs on the path as received, so {@code /cms/render/{}default/} is never
 *       mistaken for authoring.</li>
 *   <li>the hostname is normalised (every trailing dot stripped, so {@code siteb.example.} is
 *       {@code siteb.example}) and resolved here through {@link JahiaSitesService}. The request
 *       attribute cached by {@code ServerNameToSiteMapper} is neither read nor written, so this
 *       filter cannot poison what Jahia's own rewrite rules see.</li>
 * </ul>
 *
 * <p>Only a site that actually exists is treated as a cross-site target. An unknown key is not a
 * cross-site access: Jahia resolves it to no site, falls back to the hostname's site and answers
 * 404 on its own. {@code systemsite} holds content shared by every site and is always allowed.
 *
 * <p>Only live content is enforced. Authoring and default-workspace paths
 * ({@code /cms/edit/default}, {@code /files/default}, ...) are left to Jahia's login and ACLs,
 * since authors legitimately open any site from whatever hostname they are on.
 *
 * <p>While Jahia renders an error page (the {@code javax.servlet.error.status_code} attribute is
 * set) nothing is enforced: the error servlet may forward to a site-level error page under
 * {@code /errors/sites/<key>/}, which must not be re-blocked into a bare 404.
 *
 * <p>The control is hostname-driven, so it stands down entirely when the request's hostname maps to
 * no site (a bare IP, an internal load-balancer name) or is localhost. That is documented
 * fail-open behaviour and not something this filter can tighten.
 *
 * <p>Order 0.5 is deliberate: after the Shiro filter (-3) and the JCR session filter (-1), which
 * must have run before any site or content lookup, and before the {@code UrlRewriteFilter} (1), so
 * that a foreign path is refused before the rewrite rules or the renderer touch it.
 */
@Component(service = AbstractServletFilter.class)
public class CrossSitesFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrossSitesFilter.class);

    static final String SITES_PATH_PART = JahiaSitesService.SITES_JCR_PATH + "/";

    /** Authoring / default-workspace paths: /cms/{edit,editframe,render,contribute,...}/default, /files/default, /repository/default. */
    private static final Pattern AUTHORING_PATH = Pattern.compile("^/(?:cms/[^/]+|files|repository)/default(?:/|$)");

    /** ASCII and C1 controls (incl. NEL U+0085) plus the Unicode line and paragraph separators. */
    private static final Pattern LOG_UNSAFE_CHARS = Pattern.compile("[\\p{Cc}\\u0085\\u2028\\u2029]");

    private static final int MAX_LOGGED_PATH_LENGTH = 512;
    private static final long REWARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** Seam over {@link JahiaSitesService#getSitenameByServerName(String)}, which throws a checked exception. */
    @FunctionalInterface
    interface ServerNameLookup {
        String siteKeyFor(String serverName) throws Exception;
    }

    private final Supplier<? extends Collection<String>> siteNames;
    private final ServerNameLookup serverNameLookup;
    private final Logger logger;
    private final FailureTracker siteListFailure;
    private final FailureTracker hostLookupFailure;

    /** Required by OSGi DS: wires the real Jahia services. */
    public CrossSitesFilter() {
        this(() -> JahiaSitesService.getInstance().getSitesNames(),
                host -> JahiaSitesService.getInstance().getSitenameByServerName(host),
                LOGGER, System::currentTimeMillis);
    }

    CrossSitesFilter(Supplier<? extends Collection<String>> siteNames, ServerNameLookup serverNameLookup,
                     Logger logger, LongSupplier clockMillis) {
        this.siteNames = siteNames;
        this.serverNameLookup = serverNameLookup;
        this.logger = logger;
        this.siteListFailure = new FailureTracker("the site list", logger, clockMillis);
        this.hostLookupFailure = new FailureTracker("the hostname-to-site lookup", logger, clockMillis);
        setMatchAllUrls(true);
        setOrder(0.5f);
        final Set<String> types = new HashSet<>();
        types.add(DispatcherType.REQUEST.name());
        types.add(DispatcherType.FORWARD.name());
        setDispatcherTypes(types);
    }

    @Override
    public void init(FilterConfig cfg) {
        // Nothing to configure.
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final String path = getDecodedPath(httpRequest);

        // Error pages are rendered by Jahia on behalf of a request already refused or failed.
        final boolean isErrorDispatch = httpRequest.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) != null;

        if (!isErrorDispatch && !isAuthoringPath(path)) {
            final String jcrPath = normalizeJcrPath(path);
            final String siteKeyFromPath = jcrPath.contains(SITES_PATH_PART)
                    ? resolveSiteKeyFromPath(jcrPath, readSiteNames()) : null;

            // systemsite holds the content Jahia shares across every site, so a reference to it
            // from any site is legitimate by definition and must not be read as a foreign site.
            if (siteKeyFromPath != null && !JahiaSitesService.SYSTEM_SITE_KEY.equals(siteKeyFromPath)) {
                final String host = normalizeServerName(httpRequest.getServerName());
                final String siteKeyFromHost = resolveSiteKeyFromHost(host);
                if (StringUtils.isNotEmpty(siteKeyFromHost) && !siteKeyFromPath.equals(siteKeyFromHost)) {
                    logger.info(sanitise("Blocked cross-site access: path addresses site '" + siteKeyFromPath
                            + "' but hostname '" + host + "' maps to site '" + siteKeyFromHost + "', path: "
                            + abbreviate(path)));
                    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * The path as the container decoded and normalised it - the same string the renderer resolves
     * content from. Both parts are optional depending on how the request was mapped, so they are
     * concatenated rather than picked between.
     */
    private static String getDecodedPath(HttpServletRequest request) {
        return StringUtils.defaultString(request.getServletPath()) + StringUtils.defaultString(request.getPathInfo());
    }

    static boolean isAuthoringPath(String path) {
        return path != null && AUTHORING_PATH.matcher(path).find();
    }

    /**
     * JCR reads one leading {@code {}} (empty namespace) on a path segment as no marker at all.
     * Not recursive on purpose: {@code {}{}x} becomes {@code {}x}, which JCR rejects too.
     */
    static String normalizeJcrPath(String path) {
        return StringUtils.defaultString(path).replace("/{}", "/");
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

    /**
     * Mirrors {@code URLResolver#resolveSiteKeyFromPath} (jahia-impl 8.2.2.1), narrowed to keys
     * that name an existing site.
     *
     * @return the site the path addresses, or null if it addresses none
     */
    static String resolveSiteKeyFromPath(String path, Collection<String> existingSites) {
        if (path == null || existingSites == null || existingSites.isEmpty()
                || !path.contains(SITES_PATH_PART)) {
            return null;
        }

        // ".../sites/<key>/..." - the common case.
        final String keyBetweenSlashes = StringUtils.substringBetween(path, SITES_PATH_PART, "/");
        if (StringUtils.isNotEmpty(keyBetweenSlashes)) {
            return existingSites.contains(keyBetweenSlashes) ? keyBetweenSlashes : null;
        }

        // ".../sites/<key>", ".../sites/<key>.<type>" or ".../sites/<key>.<template>.<type>".
        // A site key may itself contain a dot, so where the key ends cannot be decided by looking
        // at the string: the candidates are tried against the real sites, longest first.
        String candidate = StringUtils.substringAfter(path, SITES_PATH_PART);
        while (StringUtils.isNotEmpty(candidate)) {
            if (existingSites.contains(candidate)) {
                return candidate;
            }
            if (!StringUtils.contains(candidate, ".")) {
                return null;
            }
            candidate = StringUtils.substringBeforeLast(candidate, ".");
        }
        return null;
    }

    /**
     * Cache-backed in Jahia, so cheap enough per request. It can still fail while the repository is
     * starting, and a filter that threw there would take every request down with it; no list means
     * "resolve no site", which leaves the request to Jahia.
     */
    private Collection<String> readSiteNames() {
        try {
            final Collection<String> names = siteNames.get();
            if (names == null || names.isEmpty()) {
                // Jahia always has at least systemsite, so an empty list is as unusable as null.
                siteListFailure.failed(names == null ? "returned null" : "returned no site", null);
                return null;
            }
            siteListFailure.succeeded();
            return names;
        } catch (RuntimeException e) {
            siteListFailure.failed("threw " + e, e);
            return null;
        }
    }

    private String resolveSiteKeyFromHost(String host) {
        if (host.isEmpty() || Url.isLocalhost(host)) {
            return null;
        }
        try {
            final String siteKey = serverNameLookup.siteKeyFor(host);
            hostLookupFailure.succeeded();
            if (StringUtils.isEmpty(siteKey)) {
                logger.debug(sanitise("Hostname '" + host + "' maps to no site, cross-site check skipped"));
            }
            return siteKey;
        } catch (Exception e) {
            hostLookupFailure.failed("threw " + e, e);
            return null;
        }
    }

    private static String sanitise(String message) {
        return LOG_UNSAFE_CHARS.matcher(message).replaceAll("?");
    }

    /** The path is attacker-chosen, so only a bounded prefix goes to the log. */
    private static String abbreviate(String path) {
        return path.length() > MAX_LOGGED_PATH_LENGTH ? path.substring(0, MAX_LOGGED_PATH_LENGTH) + "\u2026" : path;
    }

    boolean isSiteListFailing() {
        return siteListFailure.isFailing();
    }

    boolean isHostLookupFailing() {
        return hostLookupFailure.isFailing();
    }

    @Override
    public void destroy() {
        // Nothing to release.
    }

    /**
     * WARN on the transition to failing and again at most every ten minutes while it lasts, INFO on
     * recovery, DEBUG in between.
     */
    private static final class FailureTracker {
        private final String what;
        private final Logger logger;
        private final LongSupplier clockMillis;
        private boolean failing;
        private long lastWarnMillis;

        FailureTracker(String what, Logger logger, LongSupplier clockMillis) {
            this.what = what;
            this.logger = logger;
            this.clockMillis = clockMillis;
        }

        synchronized void failed(String reason, Exception cause) {
            final String message = "Reading " + what + " " + reason + ", letting requests through unchecked";
            final long now = clockMillis.getAsLong();
            if (!failing || now - lastWarnMillis >= REWARN_INTERVAL_MILLIS) {
                failing = true;
                lastWarnMillis = now;
                if (cause != null) {
                    logger.warn(message, cause);
                } else {
                    logger.warn(message);
                }
            } else if (cause != null) {
                logger.debug(message, cause);
            } else {
                logger.debug(message);
            }
        }

        synchronized void succeeded() {
            if (failing) {
                failing = false;
                logger.info("Reading " + what + " recovered, cross-site checks are effective again");
            }
        }

        synchronized boolean isFailing() {
            return failing;
        }
    }
}
