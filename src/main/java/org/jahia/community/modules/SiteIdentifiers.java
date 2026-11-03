package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.sites.JahiaSitesService;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Whether a {@code jsite} value is the identifier of the hostname's site node (or of systemsite).
 * Used by the servlet layer; the render layer reads the site Jahia resolved from {@code jsite} instead.
 */
final class SiteIdentifiers {

    /** Seam over reading a site node's JCR identifier, the value {@code jsite} carries. */
    @FunctionalInterface
    interface SiteIdLookup {
        /** @return the identifier of the site node, or null when there is none */
        String siteIdFor(String siteKey) throws RepositoryException;
    }

    private final SiteIdLookup siteIdLookup;
    private final FailureTracker siteIdLookupFailure;
    /** Site key to site-node identifier; bounded by the number of sites, keys come from Jahia only. */
    private final Map<String, String> siteIds = new ConcurrentHashMap<>();

    SiteIdentifiers(SiteIdLookup siteIdLookup, Logger logger, LongSupplier clockMillis) {
        this.siteIdLookup = siteIdLookup;
        this.siteIdLookupFailure = new FailureTracker("the site-identifier lookup", logger, clockMillis);
    }

    /** Fails open, like the other lookups: an unreadable identifier lets the request through, with a WARN. */
    boolean isAllowedSiteId(String id, String siteKeyFromHost) {
        try {
            final boolean allowed = isSiteId(id, siteKeyFromHost) || isSiteId(id, JahiaSitesService.SYSTEM_SITE_KEY);
            siteIdLookupFailure.succeeded();
            return allowed;
        } catch (RepositoryException | RuntimeException e) {
            siteIdLookupFailure.failed(FailureTracker.LOOKUP_FAILED, e);
            return true;
        }
    }

    /**
     * Served from the cache when it matches; anything else is re-read once before it is refused, so a
     * site deleted and re-created under the same key is recognised with its new identifier. JCR
     * identifiers are hexadecimal UUIDs, so letter case does not distinguish two nodes.
     */
    private boolean isSiteId(String id, String siteKey) throws RepositoryException {
        if (id.equalsIgnoreCase(siteIds.get(siteKey))) {
            return true;
        }
        final String current = siteIdLookup.siteIdFor(siteKey);
        if (StringUtils.isEmpty(current)) {
            throw new RepositoryException("no identifier for site " + siteKey);
        }
        siteIds.put(siteKey, current);
        return id.equalsIgnoreCase(current);
    }

    /**
     * The site node's identifier, read with Jahia's thread-bound system session (released by Jahia
     * at the end of the request, as its own site caches do) so the visitor's ACLs cannot hide it.
     * The site node keeps the same identifier in both workspaces.
     */
    static String readSiteId(String siteKey) throws RepositoryException {
        return JahiaSitesService.getInstance()
                .getSiteByKey(siteKey, JCRSessionFactory.getInstance().getCurrentSystemSession(Constants.LIVE_WORKSPACE, null, null))
                .getIdentifier();
    }

    boolean isFailing() {
        return siteIdLookupFailure.isFailing();
    }
}
