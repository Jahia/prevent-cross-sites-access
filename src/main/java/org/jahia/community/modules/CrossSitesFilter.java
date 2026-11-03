package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.services.seo.urlrewrite.ServerNameToSiteMapper;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Flags a request whose path addresses one site while the request's hostname maps to another, so
 * that the module's urlrewrite rule can answer 400.
 *
 * <p>The whole control rests on answering one question the same way Jahia answers it: <em>which
 * site does this URL address?</em> If this filter and the renderer disagree, the filter is either
 * blocking content nobody asked for or waving through the cross-site access it exists to stop.
 * Two deliberate choices follow from that:
 *
 * <ul>
 *   <li>the match runs on the <em>decoded</em> path, not on {@code getRequestURI()}. The renderer
 *       resolves content from {@code getPathInfo()}, which the container has already decoded;
 *       matching on the raw request-target let {@code /%73ites/} slip past here while the
 *       renderer still read it as {@code /sites/}.</li>
 *   <li>the site key is extracted the way {@code org.jahia.services.render.URLResolver} extracts
 *       it, including the trailing-segment and template-extension forms that a single
 *       {@code substringBetween} misses entirely.</li>
 * </ul>
 *
 * <p>Only a site that actually exists is treated as a cross-site target. An unknown key is not a
 * cross-site access: Jahia resolves it to no site, falls back to the hostname's site and answers
 * 404 on its own. Blocking those too would turn every unrelated path that merely contains
 * {@code /sites/} - a module's own static resources, for instance - into a 400.
 *
 * <p>Note that the control is hostname-driven, so it stands down entirely when the request's
 * hostname maps to no site (a bare IP, an internal load-balancer name) or is localhost. That is
 * inherited from {@link ServerNameToSiteMapper} and is not something this filter can tighten.
 */
@Component(service = AbstractServletFilter.class)
public class CrossSitesFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrossSitesFilter.class);

    private static final String PATH_PART_SITES = "/sites/";
    private static final String BLOCKED_REQUEST_ATTRIBUTE = "prevent_cross_script";

    public CrossSitesFilter() {
        setMatchAllUrls(true);
        setOrder(0.5f);
    }

    @Override
    public void init(FilterConfig cfg) {
        // Nothing to configure.
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final String siteKeyFromPath = resolveSiteKeyFromPath(getDecodedPath(httpRequest));

        // systemsite holds the content Jahia shares across every site, so a reference to it from
        // any site is legitimate by definition and must not be read as a foreign site.
        if (siteKeyFromPath != null && !JahiaSitesService.SYSTEM_SITE_KEY.equals(siteKeyFromPath)) {
            final String siteKeyFromServerName = ServerNameToSiteMapper.getSiteKeyByServerName(httpRequest);
            if (StringUtils.isNotEmpty(siteKeyFromServerName) && !siteKeyFromPath.equals(siteKeyFromServerName)) {
                LOGGER.debug("Blocking cross-site access to '{}' over a hostname mapped to '{}': {}",
                        siteKeyFromPath, siteKeyFromServerName, httpRequest.getRequestURI());
                httpRequest.setAttribute(BLOCKED_REQUEST_ATTRIBUTE, "true");
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

    /**
     * Mirrors {@code URLResolver#resolveSiteKeyFromPath}, narrowed to keys that name an existing
     * site.
     *
     * @return the site the path addresses, or null if it addresses none
     */
    private static String resolveSiteKeyFromPath(String path) {
        if (!path.contains(PATH_PART_SITES)) {
            return null;
        }

        final List<String> siteNames = getSiteNames();
        if (siteNames.isEmpty()) {
            return null;
        }

        // ".../sites/<key>/..." - the common case.
        final String keyBetweenSlashes = StringUtils.substringBetween(path, PATH_PART_SITES, "/");
        if (StringUtils.isNotEmpty(keyBetweenSlashes)) {
            return siteNames.contains(keyBetweenSlashes) ? keyBetweenSlashes : null;
        }

        // ".../sites/<key>", ".../sites/<key>.<type>" or ".../sites/<key>.<template>.<type>".
        // A site key may itself contain a dot, so where the key ends cannot be decided by looking
        // at the string: the candidates are tried against the real sites, longest first.
        String candidate = StringUtils.substringAfter(path, PATH_PART_SITES);
        while (StringUtils.isNotEmpty(candidate)) {
            if (siteNames.contains(candidate)) {
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
     * Cache-backed, so this is cheap enough to call per request. It can still fail while the
     * repository is starting, and a filter that threw there would take every request down with
     * it; an empty list means "resolve no site", which leaves the request to Jahia.
     */
    private static List<String> getSiteNames() {
        try {
            final List<String> siteNames = JahiaSitesService.getInstance().getSitesNames();
            return siteNames != null ? siteNames : Collections.emptyList();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the site list, letting the request through unchecked", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void destroy() {
        // Nothing to release.
    }
}
