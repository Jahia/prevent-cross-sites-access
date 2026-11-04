package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.jahia.services.render.filter.RenderFilter;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Second, authoritative layer for rendered content: refuses a live main resource whose site, as Jahia
 * resolved it, is another existing site than the hostname's.
 *
 * <p>The servlet layer ({@link CrossSitesFilter}) reads the URL and the parameters as strings, and
 * each review round found a form where that reading disagreed with Jahia's ({@code %73ites}, a second
 * decoding on FORWARD, {@code {}name}, trailing {@code /} and {@code /*}, {@code jsite}, the
 * {@code /sites} root). This filter reads no string: in {@code prepare} of the main resource it takes
 * {@code resource.getNode().getResolveSite()} (from the node's canonical path) and
 * {@code renderContext.getSite()} (set by {@code Render#resolveSite} from the path, the hostname or
 * {@code jsite}), and blocks when either is an existing site other than the hostname's site and
 * systemsite. The main resource being the {@code /sites} node or the repository root, whose resolve
 * site is systemsite but which list every site, is refused as well.
 *
 * <p><b>{@code jarea}.</b> Jahia's {@code TemplateAjaxFilter} (priority 22.1, after the caching
 * filters) renders the node a {@code jarea} UUID names inside the page, as a nested resource this
 * filter would not otherwise see, and the cache key of the page ignores the parameter. A
 * {@code jarea} naming a node of another site than the page's own (systemsite and nodes outside
 * {@code /sites}, such as template areas, excepted), or the {@code /sites} node or the repository
 * root, is therefore refused on every hostname, mapped or not: rendered once on an unmapped hostname,
 * the foreign fragment would be cached under the page and served to the page's own visitors. The node
 * is looked up exactly as {@code TemplateAjaxFilter} does it, with the page node's session.
 *
 * <p>It does not replace the servlet layer: {@code /files} and {@code /repository} never enter the
 * render chain, actions ({@code *.do}, POST) run in {@code Render} before it, and search hits are
 * printed by the search results JSP rather than rendered as resources.
 *
 * <p>Registration, as Jahia's own module filters do it (e.g. site-settings-seo's {@code SeoUrlFilter}):
 * a DS component providing {@link RenderFilter}, live mode only, main resource only. Sub-resources are
 * not checked, as a page legitimately includes systemsite content and content references to another
 * site. Priority {@value #PRIORITY} puts it before every core filter, in particular before the
 * caching filters (16 and 16.5), so a page served from cache is still checked.
 *
 * <p>A block logs one sanitised INFO line, sets the {@code siteKey} request attribute to the
 * hostname's site and throws {@link PathNotFoundException}: {@code RenderChain} wraps it in a
 * {@code RenderFilterException}, which {@code DefaultErrorHandler} unwraps and answers with 404, and
 * {@code ErrorServlet} renders the hostname's site's error page (for a {@code jarea} refused on an
 * unmapped hostname, the page's own site's). Like the servlet layer it stands down on an unmapped,
 * localhost or empty hostname (the {@code jarea} check excepted), during error dispatches, and when a
 * lookup fails (with the rate-limited WARN of {@link FailureTracker}).
 */
@Component(service = RenderFilter.class, immediate = true)
public class CrossSitesRenderFilter extends AbstractFilter {

    /** Before every core render filter (the lowest is -2) and well before the caching filters (16). */
    static final float PRIORITY = -10f;

    private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(CrossSitesRenderFilter.class);

    /** The repository root and the {@code /sites} node: their resolve site is systemsite, yet they list every site. */
    private static final String REPOSITORY_ROOT = "/";

    /** The UUID of a node {@code TemplateAjaxFilter} renders inside the page. */
    static final String AREA_PARAMETER = "jarea";

    private final SiteDirectory directory;
    private final Logger logger;
    private final FailureTracker resolveSiteFailure;
    private final FailureTracker areaLookupFailure;

    /** Required by OSGi DS: wires the real Jahia services. */
    public CrossSitesRenderFilter() {
        this(SiteDirectory.forJahia(DEFAULT_LOGGER), DEFAULT_LOGGER, System::currentTimeMillis);
    }

    CrossSitesRenderFilter(SiteDirectory directory, Logger logger, LongSupplier clockMillis) {
        this.directory = directory;
        this.logger = logger;
        this.resolveSiteFailure = new FailureTracker("the site of the main resource", logger, clockMillis);
        this.areaLookupFailure = new FailureTracker("the node named by jarea", logger, clockMillis);
        setPriority(PRIORITY);
        setApplyOnModes("live");
        setApplyOnMainResource(true);
        setDescription("Refuses a live main resource of another site than the request's hostname maps to");
    }

    /**
     * Same identity as {@link AbstractFilter}: class and priority, which is what Jahia's render filter
     * registry compares. The collaborators are deliberately left out: they carry no filter identity.
     */
    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Activate
    public void activate() {
        logger.info("Cross-site access render filter active: priority {}, live mode, main resource only", getPriority());
    }

    @Override
    public String prepare(RenderContext renderContext, Resource resource, RenderChain chain) throws Exception {
        final HttpServletRequest request = renderContext.getRequest();
        if (request == null || request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) != null) {
            return null;
        }
        final SiteDirectory.HostSite hostSite = new SiteDirectory.HostSite(directory, request);
        final JCRNodeWrapper node = resource.getNode();
        final String areaTarget = foreignArea(request, node);
        if (areaTarget != null) {
            final String siteKeyFromHost = hostSite.siteKey();
            final String errorSite = StringUtils.isEmpty(siteKeyFromHost) ? siteKeyOfPath(node.getPath()) : siteKeyFromHost;
            if (logger.isInfoEnabled()) {
                logger.info("Blocked cross-site access (render layer): {} selects {} on a page of site '{}', hostname '{}', main resource: {}, referer: {}",
                        AREA_PARAMETER, LogValues.safe(areaTarget), LogValues.sanitise(StringUtils.defaultString(errorSite, "-")),
                        LogValues.sanitise(hostSite.host()), LogValues.safe(node.getPath()),
                        LogValues.describeReferer(request.getHeader("Referer")));
            }
            if (errorSite != null) {
                request.setAttribute(SiteDirectory.SITE_KEY_ATTRIBUTE, errorSite);
            }
            throw new PathNotFoundException("Not found");
        }
        final String siteKeyFromHost = hostSite.siteKey();
        if (StringUtils.isEmpty(siteKeyFromHost)) {
            return null;
        }
        final Collection<String> siteNames = directory.siteNames();
        if (siteNames.isEmpty()) {
            return null;
        }
        final String target = foreignTarget(node, renderContext, SiteDirectory.toSet(siteNames), siteKeyFromHost);
        if (target == null) {
            return null;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Blocked cross-site access (render layer): main resource resolves to {} but hostname '{}' maps to site '{}', main resource: {}, referer: {}",
                    LogValues.safe(target), LogValues.sanitise(hostSite.host()), LogValues.sanitise(siteKeyFromHost),
                    LogValues.safe(node == null ? "-" : node.getPath()), LogValues.describeReferer(request.getHeader("Referer")));
        }
        // ErrorServlet reads this attribute before it resolves the URL, as for the servlet layer.
        request.setAttribute(SiteDirectory.SITE_KEY_ATTRIBUTE, siteKeyFromHost);
        throw new PathNotFoundException("Not found");
    }

    /** What the main resource reaches beyond the hostname's site and systemsite, or null. */
    private String foreignTarget(JCRNodeWrapper node, RenderContext renderContext, Set<String> sites, String siteKeyFromHost) {
        if (node != null && (REPOSITORY_ROOT.equals(node.getPath()) || JahiaSitesService.SITES_JCR_PATH.equals(node.getPath()))) {
            return "every site (the node '" + node.getPath() + "')";
        }
        final String nodeSite = resolveSiteKey(node);
        if (SiteDirectory.isForeignSite(nodeSite, sites, siteKeyFromHost)) {
            return "site '" + nodeSite + "'";
        }
        final JCRSiteNode contextSite = renderContext.getSite();
        final String contextSiteKey = contextSite == null ? null : contextSite.getSiteKey();
        if (SiteDirectory.isForeignSite(contextSiteKey, sites, siteKeyFromHost)) {
            return "render-context site '" + contextSiteKey + "'";
        }
        return null;
    }

    /**
     * What a {@code jarea} parameter makes Jahia render beyond the page's own site and systemsite, or
     * null. Independent of the hostname: see the class comment.
     */
    private String foreignArea(HttpServletRequest request, JCRNodeWrapper page) {
        final String areaId = request.getParameter(AREA_PARAMETER);
        if (StringUtils.isBlank(areaId) || page == null) {
            return null;
        }
        final String areaPath;
        try {
            areaPath = page.getSession().getNodeByUUID(areaId).getPath();
            areaLookupFailure.succeeded();
        } catch (ItemNotFoundException e) {
            // No node this session can read: TemplateAjaxFilter's identical lookup fails too.
            areaLookupFailure.succeeded();
            return null;
        } catch (RepositoryException | RuntimeException e) {
            areaLookupFailure.failed(FailureTracker.LOOKUP_FAILED, e);
            return null;
        }
        if (REPOSITORY_ROOT.equals(areaPath) || JahiaSitesService.SITES_JCR_PATH.equals(areaPath)) {
            return "every site (the node '" + areaPath + "')";
        }
        final String areaSite = siteKeyOfPath(areaPath);
        if (areaSite == null || JahiaSitesService.SYSTEM_SITE_KEY.equals(areaSite)
                || areaSite.equals(siteKeyOfPath(page.getPath()))) {
            return null;
        }
        return "site '" + areaSite + "' (the node '" + areaPath + "')";
    }

    /** The site key of a canonical JCR path under {@code /sites/<key>}, or null. */
    static String siteKeyOfPath(String path) {
        if (path == null || !path.startsWith(SitePaths.SITES_PATH_PART)) {
            return null;
        }
        return StringUtils.defaultIfEmpty(StringUtils.substringBefore(path.substring(SitePaths.SITES_PATH_PART.length()), "/"), null);
    }

    /** Null when the node has no site or it cannot be read; the render-context site is still checked then. */
    private String resolveSiteKey(JCRNodeWrapper node) {
        if (node == null) {
            return null;
        }
        try {
            final JCRSiteNode site = node.getResolveSite();
            resolveSiteFailure.succeeded();
            return site == null ? null : site.getSiteKey();
        } catch (RepositoryException | RuntimeException e) {
            resolveSiteFailure.failed(FailureTracker.LOOKUP_FAILED, e);
            return null;
        }
    }

    boolean isResolvedSiteFailing() {
        return resolveSiteFailure.isFailing();
    }
}
