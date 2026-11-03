package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.community.modules.SearchParameterCheck.ParameterViolation;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Activate;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 *   <li>the hostname is normalised and resolved as described in {@link SiteDirectory}.</li>
 * </ul>
 *
 * <p>Once the path passes, the request parameters that select a site are checked against the same
 * hostname, since a path naming the visitor's own site can still render or search another one:
 *
 * <ul>
 *   <li>{@code jsite}, which {@code org.jahia.bin.Render} (jahia-impl 8.2.2.1, around line 1115)
 *       reads with {@code getParameter} and uses to swap the render-context site for the node with
 *       that identifier. Anything but the identifier of the hostname's site (or of systemsite) is
 *       refused.</li>
 *   <li>the search parameters, prefix {@code src_}, which {@code SearchCriteriaFactory} (jahia-impl)
 *       copies onto a {@code SearchCriteria} bean with BeanUtils for the search module's
 *       {@code <s:results>} tag. Only a request on which Jahia would actually run a search is
 *       refused, and only when its scope reaches another existing site, every site or the whole
 *       repository. The rules mirrored, with the Jahia classes and version checked, are in
 *       {@link SearchParameterCheck}.</li>
 * </ul>
 *
 * <p>A live path addressing the {@code /sites} node itself ({@code /sites.html.ajax},
 * {@code /sites/.html.ajax}, ...) is refused on a mapped hostname too: it names no site key, yet the
 * renderer lists every site's title, server name and top-level nodes from it.
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
 * <p>This is the first of two layers. {@link CrossSitesRenderFilter}, in the render chain, refuses a
 * live main resource by what Jahia actually resolved; this servlet filter covers what never reaches
 * the render chain ({@code /files}, {@code /repository}, actions, search hits that a JSP prints) and
 * refuses rendered URLs earlier, from their string form. The shared pieces live in
 * {@link SiteDirectory} (hostname and site list), {@link SitePaths}, {@link SiteIdentifiers},
 * {@link SearchParameterCheck}, {@link FailureTracker} and {@link LogValues}.
 *
 * <p>Order 0.5 is deliberate: after the Shiro filter (-3) and the JCR session filter (-1), which
 * must have run before any site or content lookup, and before the {@code UrlRewriteFilter} (1), so
 * that a foreign path is refused before the rewrite rules or the renderer touch it. Those orders are
 * defined in jahia-impl's {@code applicationcontext-basejahiaconfig.xml} (checked against 8.2.2.1);
 * nothing in this build verifies them, so re-check that file when moving to a new Jahia line.
 */
@Component(service = AbstractServletFilter.class)
public class CrossSitesFilter extends AbstractServletFilter {

    private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(CrossSitesFilter.class);

    /** Request parameter {@code org.jahia.bin.Render} reads to swap the render-context site. */
    static final String JSITE_PARAMETER = "jsite";

    private final SiteDirectory directory;
    private final SiteIdentifiers siteIdentifiers;
    private final Logger logger;

    /** Required by OSGi DS: wires the real Jahia services. */
    public CrossSitesFilter() {
        this(() -> JahiaSitesService.getInstance().getSitesNames(),
                host -> JahiaSitesService.getInstance().getSitenameByServerName(host),
                SiteIdentifiers::readSiteId, DEFAULT_LOGGER, System::currentTimeMillis);
    }

    CrossSitesFilter(Supplier<? extends Collection<String>> siteNames, SiteDirectory.ServerNameLookup serverNameLookup,
                     SiteIdentifiers.SiteIdLookup siteIdLookup, Logger logger, LongSupplier clockMillis) {
        this.directory = new SiteDirectory(siteNames, serverNameLookup, logger, clockMillis);
        this.siteIdentifiers = new SiteIdentifiers(siteIdLookup, logger, clockMillis);
        this.logger = logger;
        setMatchAllUrls(true);
        setOrder(0.5f);
        final Set<String> types = new HashSet<>();
        types.add(DispatcherType.REQUEST.name());
        types.add(DispatcherType.FORWARD.name());
        setDispatcherTypes(types);
    }

    @Activate
    public void activate() {
        logger.info("Cross-site access filter active: order {}, dispatcher types {}", getOrder(),
                new TreeSet<>(getDispatcherTypes()));
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

        if (!isErrorDispatch && !SitePaths.isAuthoringPath(path)) {
            final SiteDirectory.HostSite hostSite = new SiteDirectory.HostSite(directory, httpRequest);
            if (isBlockedByPath(httpRequest, path, hostSite) || isBlockedByParameter(httpRequest, path, hostSite)) {
                // ErrorServlet reads this attribute before it resolves the URL: without it the
                // 404 page would carry the branding and language of the site in the path.
                httpRequest.setAttribute(SiteDirectory.SITE_KEY_ATTRIBUTE, hostSite.siteKey());
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isBlockedByPath(HttpServletRequest httpRequest, String path, SiteDirectory.HostSite hostSite) {
        final String jcrPath = SitePaths.normalizeJcrPath(path);
        final String siteKeyFromPath = jcrPath.contains(SitePaths.SITES_PATH_PART)
                ? SitePaths.resolveSiteKeyFromPath(jcrPath, directory.siteNames()) : null;
        final boolean isSitesRoot = siteKeyFromPath == null && SitePaths.addressesSitesRoot(jcrPath);

        // systemsite holds the content Jahia shares across every site, so a reference to it
        // from any site is legitimate by definition and must not be read as a foreign site.
        if (!isSitesRoot && (siteKeyFromPath == null || JahiaSitesService.SYSTEM_SITE_KEY.equals(siteKeyFromPath))) {
            return false;
        }
        final String siteKeyFromHost = hostSite.siteKey();
        if (StringUtils.isEmpty(siteKeyFromHost) || !isSitesRoot && siteKeyFromPath.equals(siteKeyFromHost)) {
            return false;
        }
        if (logger.isInfoEnabled()) {
            // Placeholder arguments are not escaped by SLF4J and the host and path are
            // attacker-chosen, so each one is sanitised (and the path capped) first.
            logger.info("Blocked cross-site access: path addresses {} but hostname '{}' maps to site '{}', path: {}, referer: {}",
                    isSitesRoot ? "the sites root '" + JahiaSitesService.SITES_JCR_PATH + "'" : "site '" + LogValues.sanitise(siteKeyFromPath) + "'",
                    LogValues.sanitise(hostSite.host()), LogValues.sanitise(siteKeyFromHost),
                    LogValues.safe(path), LogValues.describeReferer(httpRequest.getHeader("Referer")));
        }
        return true;
    }

    /**
     * Reading parameters here is safe and is what the renderer reads: Jahia's
     * {@code CharacterEncodingFilter} (order -6) has set UTF-8 before this filter runs, and its login
     * valve already calls {@code getParameter} on every request, so the container has parsed the query
     * string and any form-urlencoded POST body by now. That parse is kept by the container and is what
     * {@code Render} and {@code SearchCriteriaFactory} see later. The body itself is never read here:
     * JSON and multipart bodies are not parsed by {@code getParameter}, and Jahia's renderer does not
     * feed multipart fields to {@code jsite} or to the search criteria either.
     */
    private boolean isBlockedByParameter(HttpServletRequest httpRequest, String path, SiteDirectory.HostSite hostSite) {
        final Map<String, String[]> parameters = httpRequest.getParameterMap();
        if (parameters == null || !hasSiteSelectingParameter(parameters.keySet())) {
            return false;
        }
        final String siteKeyFromHost = hostSite.siteKey();
        if (StringUtils.isEmpty(siteKeyFromHost)) {
            return false;
        }
        ParameterViolation violation = findJsiteViolation(parameters.get(JSITE_PARAMETER), siteKeyFromHost);
        if (violation == null) {
            final Collection<String> sites = directory.siteNames();
            // No site list means "resolve no site", as for the path: the search is left to Jahia.
            violation = sites.isEmpty() ? null : SearchParameterCheck.findViolation(parameters, SiteDirectory.toSet(sites), siteKeyFromHost);
        }
        if (violation == null) {
            return false;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Blocked cross-site access: parameter '{}' selects {} but hostname '{}' maps to site '{}', path: {}, referer: {}",
                    LogValues.safe(violation.parameter), LogValues.safe(violation.target),
                    LogValues.sanitise(hostSite.host()), LogValues.sanitise(siteKeyFromHost), LogValues.safe(path),
                    LogValues.describeReferer(httpRequest.getHeader("Referer")));
        }
        return true;
    }

    private static boolean hasSiteSelectingParameter(Collection<String> names) {
        for (String name : names) {
            if (JSITE_PARAMETER.equals(name) || SearchParameterCheck.isSearchParameter(name)) {
                return true;
            }
        }
        return false;
    }

    /** Every value counts, not only the first one {@code Render} reads, so ordering cannot matter. */
    private ParameterViolation findJsiteViolation(String[] values, String siteKeyFromHost) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            // A blank jsite selects no site: Render fails on it with an error of its own.
            if (StringUtils.isNotBlank(value) && !siteIdentifiers.isAllowedSiteId(value, siteKeyFromHost)) {
                return new ParameterViolation(JSITE_PARAMETER, "site node '" + value + "'");
            }
        }
        return null;
    }

    /**
     * The path as the container decoded and normalised it - the same string the renderer resolves
     * content from. Both parts are optional depending on how the request was mapped, so they are
     * concatenated rather than picked between.
     */
    private static String getDecodedPath(HttpServletRequest request) {
        return StringUtils.defaultString(request.getServletPath()) + StringUtils.defaultString(request.getPathInfo());
    }

    boolean isSiteListFailing() {
        return directory.isSiteListFailing();
    }

    boolean isHostLookupFailing() {
        return directory.isHostLookupFailing();
    }

    boolean isSiteIdLookupFailing() {
        return siteIdentifiers.isFailing();
    }

    @Override
    public void destroy() {
        // Nothing to release.
    }
}
