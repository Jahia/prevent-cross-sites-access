package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.exceptions.JahiaException;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.community.modules.SearchParameterCheck.ParameterViolation;
import org.jahia.utils.Url;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Order 0.5 is deliberate: after the Shiro filter (-3) and the JCR session filter (-1), which
 * must have run before any site or content lookup, and before the {@code UrlRewriteFilter} (1), so
 * that a foreign path is refused before the rewrite rules or the renderer touch it. Those orders are
 * defined in jahia-impl's {@code applicationcontext-basejahiaconfig.xml} (checked against 8.2.2.1);
 * nothing in this build verifies them, so re-check that file when moving to a new Jahia line.
 */
@Component(service = AbstractServletFilter.class)
public class CrossSitesFilter extends AbstractServletFilter {

    private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(CrossSitesFilter.class);

    static final String SITES_PATH_PART = JahiaSitesService.SITES_JCR_PATH + '/';

    /**
     * Authoring / default-workspace paths: /cms/{edit,editframe,render,contribute,...}/default,
     * /repository/default, /files/default. FileServlet maps the CKEditor placeholder workspace
     * {workspace} to the default (edit) workspace, so /files/{workspace} is exempt as well.
     */
    private static final Pattern AUTHORING_PATH = Pattern.compile(
            "^/(?:(?:cms/[^/]+|repository)/default|files/(?:default|\\{workspace\\}))(?:/|$)");

    /** ASCII and C1 controls (incl. NEL U+0085), Unicode format characters (bidi overrides, zero-width) and the line and paragraph separators. */
    private static final Pattern LOG_UNSAFE_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}\\u2028\\u2029]");

    /** Request parameter {@code org.jahia.bin.Render} reads to swap the render-context site. */
    static final String JSITE_PARAMETER = "jsite";

    /** Name of the repository node every site lives under ({@code /sites}). */
    static final String SITES_NODE_NAME = "sites";

    /** Servlets whose URL is {@code /<servlet>/<workspace>} followed by a JCR path. */
    private static final Set<String> WORKSPACE_SERVLETS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("files", "repository")));
    /** Reason logged when a lookup throws. */
    private static final String LOOKUP_FAILED = "failed";

    private static final int MAX_LOGGED_LENGTH = 512;
    private static final String SITE_KEY_ATTRIBUTE = "siteKey";
    private static final long REWARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** Seam over {@link JahiaSitesService#getSitenameByServerName(String)}, which throws a checked exception. */
    @FunctionalInterface
    interface ServerNameLookup {
        String siteKeyFor(String serverName) throws JahiaException;
    }

    /** Seam over reading a site node's JCR identifier, the value {@code jsite} carries. */
    @FunctionalInterface
    interface SiteIdLookup {
        /** @return the identifier of the site node, or null when there is none */
        String siteIdFor(String siteKey) throws RepositoryException;
    }

    private final Supplier<? extends Collection<String>> siteNames;
    private final ServerNameLookup serverNameLookup;
    private final SiteIdLookup siteIdLookup;
    private final Logger logger;
    private final FailureTracker siteListFailure;
    private final FailureTracker hostLookupFailure;
    private final FailureTracker siteIdLookupFailure;
    /** Site key to site-node identifier; bounded by the number of sites, keys come from Jahia only. */
    private final Map<String, String> siteIds = new ConcurrentHashMap<>();

    /** Required by OSGi DS: wires the real Jahia services. */
    public CrossSitesFilter() {
        this(() -> JahiaSitesService.getInstance().getSitesNames(),
                host -> JahiaSitesService.getInstance().getSitenameByServerName(host),
                CrossSitesFilter::readSiteId, DEFAULT_LOGGER, System::currentTimeMillis);
    }

    CrossSitesFilter(Supplier<? extends Collection<String>> siteNames, ServerNameLookup serverNameLookup,
                     SiteIdLookup siteIdLookup, Logger logger, LongSupplier clockMillis) {
        this.siteNames = siteNames;
        this.serverNameLookup = serverNameLookup;
        this.siteIdLookup = siteIdLookup;
        this.logger = logger;
        this.siteListFailure = new FailureTracker("the site list", logger, clockMillis);
        this.hostLookupFailure = new FailureTracker("the hostname-to-site lookup", logger, clockMillis);
        this.siteIdLookupFailure = new FailureTracker("the site-identifier lookup", logger, clockMillis);
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

        if (!isErrorDispatch && !isAuthoringPath(path)) {
            final HostSite hostSite = new HostSite(httpRequest);
            if (isBlockedByPath(httpRequest, path, hostSite) || isBlockedByParameter(httpRequest, path, hostSite)) {
                // ErrorServlet reads this attribute before it resolves the URL: without it the
                // 404 page would carry the branding and language of the site in the path.
                httpRequest.setAttribute(SITE_KEY_ATTRIBUTE, hostSite.siteKey());
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isBlockedByPath(HttpServletRequest httpRequest, String path, HostSite hostSite) {
        final String jcrPath = normalizeJcrPath(path);
        final String siteKeyFromPath = jcrPath.contains(SITES_PATH_PART)
                ? resolveSiteKeyFromPath(jcrPath, readSiteNames()) : null;
        final boolean isSitesRoot = siteKeyFromPath == null && addressesSitesRoot(jcrPath);

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
                    isSitesRoot ? "the sites root '" + JahiaSitesService.SITES_JCR_PATH + "'" : "site '" + sanitise(siteKeyFromPath) + "'",
                    sanitise(hostSite.host()), sanitise(siteKeyFromHost),
                    sanitise(abbreviate(path)), describeReferer(httpRequest.getHeader("Referer")));
        }
        return true;
    }

    /**
     * Whether the first {@code sites} segment of a normalised path is the {@code /sites} node itself,
     * at the start of the JCR path: {@code /sites}, {@code /sites.<ext...>} or {@code /sites/.<ext...>}.
     * Called only when no site key resolved, so {@code /sites/<own>/home/sites.html} never gets here.
     */
    static boolean addressesSitesRoot(String jcrPath) {
        final String root = JahiaSitesService.SITES_JCR_PATH;
        int at = jcrPath.indexOf(root);
        while (at >= 0) {
            final String rest = jcrPath.substring(at + root.length());
            if (rest.isEmpty() || rest.charAt(0) == '.' || rest.charAt(0) == '/') {
                final boolean isNodeItself = rest.isEmpty() || rest.charAt(0) == '.' || rest.equals("/") || rest.startsWith("/.");
                return isNodeItself && isUrlPrefixBeforeJcrPath(jcrPath.substring(0, at));
            }
            at = jcrPath.indexOf(root, at + 1);
        }
        return false;
    }

    /**
     * Path prefixes a JCR path follows in a URL: {@code /cms/<servlet>/<workspace>[/<lang>]},
     * {@code /files/<workspace>}, {@code /repository/<workspace>}, the SEO {@code /<lang>}, or none.
     * Every segment of the prefix is non-empty.
     */
    private static boolean isUrlPrefixBeforeJcrPath(String prefix) {
        if (prefix.isEmpty()) {
            return true;
        }
        if (prefix.charAt(0) != '/') {
            return false;
        }
        final String[] segments = prefix.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return false;
            }
        }
        switch (segments.length) {
            case 1:
                return true;
            case 2:
                return WORKSPACE_SERVLETS.contains(segments[0]);
            case 3:
            case 4:
                return "cms".equals(segments[0]);
            default:
                return false;
        }
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
    private boolean isBlockedByParameter(HttpServletRequest httpRequest, String path, HostSite hostSite) {
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
            final Collection<String> sites = readSiteNames();
            // No site list means "resolve no site", as for the path: the search is left to Jahia.
            violation = sites.isEmpty() ? null : SearchParameterCheck.findViolation(parameters, toSet(sites), siteKeyFromHost);
        }
        if (violation == null) {
            return false;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Blocked cross-site access: parameter '{}' selects {} but hostname '{}' maps to site '{}', path: {}, referer: {}",
                    sanitise(abbreviate(violation.parameter)), sanitise(abbreviate(violation.target)),
                    sanitise(hostSite.host()), sanitise(siteKeyFromHost), sanitise(abbreviate(path)),
                    describeReferer(httpRequest.getHeader("Referer")));
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
            if (!isAllowedSiteId(StringUtils.defaultString(value), siteKeyFromHost)) {
                return new ParameterViolation(JSITE_PARAMETER, "site node '" + value + "'");
            }
        }
        return null;
    }

    /** Fails open, like the other lookups: an unreadable identifier lets the request through, with a WARN. */
    private boolean isAllowedSiteId(String id, String siteKeyFromHost) {
        try {
            final boolean allowed = isSiteId(id, siteKeyFromHost) || isSiteId(id, JahiaSitesService.SYSTEM_SITE_KEY);
            siteIdLookupFailure.succeeded();
            return allowed;
        } catch (RepositoryException | RuntimeException e) {
            siteIdLookupFailure.failed(LOOKUP_FAILED, e);
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
    private static String readSiteId(String siteKey) throws RepositoryException {
        return JahiaSitesService.getInstance()
                .getSiteByKey(siteKey, JCRSessionFactory.getInstance().getCurrentSystemSession(Constants.LIVE_WORKSPACE, null, null))
                .getIdentifier();
    }

    private static Set<String> toSet(Collection<String> names) {
        return names instanceof Set ? (Set<String>) names : new HashSet<>(names);
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
     * The path as the renderer will read it. JCR reads one leading {@code {}} (empty namespace) on a
     * path segment as no marker at all; not recursive on purpose, {@code {}{}x} becomes {@code {}x},
     * which JCR rejects too. Then, in the renderer's order, one trailing "/" goes
     * ({@code URLResolver#normalizeUrlPathInfo}) and a trailing "/*" goes ({@code resolveNode}).
     */
    static String normalizeJcrPath(String path) {
        String result = StringUtils.defaultString(path).replace("/{}", "/");
        if (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("/*")) {
            result = result.substring(0, result.length() - 2);
        }
        return result;
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
        final Set<String> sites = toSet(existingSites);

        // ".../sites/<key>/..." - the common case.
        final String keyBetweenSlashes = StringUtils.substringBetween(path, SITES_PATH_PART, "/");
        if (StringUtils.isNotEmpty(keyBetweenSlashes)) {
            return sites.contains(keyBetweenSlashes) ? keyBetweenSlashes : null;
        }

        // ".../sites/<key>", ".../sites/<key>.<type>" or ".../sites/<key>.<template>.<type>".
        // A site key may itself contain a dot, so where the key ends cannot be decided by looking
        // at the string: the candidates (the whole ending, then each cut at a dot) are tried
        // against the real sites, longest first. The path is attacker-chosen, so candidates longer
        // than the longest site name are skipped without allocating them.
        final String pathEnding = StringUtils.substringAfter(path, SITES_PATH_PART);
        final int longestName = sites.stream().mapToInt(String::length).max().orElse(0);
        int end = pathEnding.length();
        while (end > 0) {
            if (end <= longestName && sites.contains(pathEnding.substring(0, end))) {
                return pathEnding.substring(0, end);
            }
            end = pathEnding.lastIndexOf('.', end - 1);
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
                return Collections.emptyList();
            }
            siteListFailure.succeeded();
            return names;
        } catch (RuntimeException e) {
            siteListFailure.failed(LOOKUP_FAILED, e);
            return Collections.emptyList();
        }
    }

    private static String sanitise(String message) {
        return LOG_UNSAFE_CHARS.matcher(message).replaceAll("?");
    }

    /** The path is attacker-chosen, so only a bounded prefix goes to the log; a surrogate pair is never cut. */
    private static String abbreviate(String text) {
        if (text.length() <= MAX_LOGGED_LENGTH) {
            return text;
        }
        final int cut = Character.isHighSurrogate(text.charAt(MAX_LOGGED_LENGTH - 1)) ? MAX_LOGGED_LENGTH - 1 : MAX_LOGGED_LENGTH;
        return text.substring(0, cut) + "\u2026";
    }

    /** The page embedding a cross-site link, so an admin can find it; "-" when the browser sent none. */
    private static String describeReferer(String referer) {
        return StringUtils.isEmpty(referer) ? "-" : sanitise(abbreviate(referer));
    }

    boolean isSiteListFailing() {
        return siteListFailure.isFailing();
    }

    boolean isHostLookupFailing() {
        return hostLookupFailure.isFailing();
    }

    boolean isSiteIdLookupFailing() {
        return siteIdLookupFailure.isFailing();
    }

    /** The request's hostname and its site, looked up at most once per request and only when needed. */
    private final class HostSite {
        private final HttpServletRequest request;
        private String host;
        private String siteKey;
        private boolean isResolved;

        HostSite(HttpServletRequest request) {
            this.request = request;
        }

        String host() {
            siteKey();
            return host;
        }

        String siteKey() {
            if (!isResolved) {
                host = normalizeServerName(request.getServerName());
                siteKey = resolveSiteKeyFromHost(host);
                isResolved = true;
            }
            return siteKey;
        }

        private String resolveSiteKeyFromHost(String serverName) {
            if (serverName.isEmpty() || Url.isLocalhost(serverName)) {
                return null;
            }
            try {
                final String key = serverNameLookup.siteKeyFor(serverName);
                hostLookupFailure.succeeded();
                if (StringUtils.isEmpty(key) && logger.isDebugEnabled()) {
                    logger.debug("Hostname '{}' maps to no site, cross-site check skipped", sanitise(serverName));
                }
                return key;
            } catch (JahiaException | RuntimeException e) {
                hostLookupFailure.failed(LOOKUP_FAILED, e);
                return null;
            }
        }
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
        // volatile: succeeded() runs on every /sites/ request and the healthy case must take no lock.
        private volatile boolean failing;
        private long lastWarnMillis;

        FailureTracker(String what, Logger logger, LongSupplier clockMillis) {
            this.what = what;
            this.logger = logger;
            this.clockMillis = clockMillis;
        }

        synchronized void failed(String reason, Exception cause) {
            final String format = "Reading {} {}, letting requests through unchecked";
            final long now = clockMillis.getAsLong();
            if (!failing || now - lastWarnMillis >= REWARN_INTERVAL_MILLIS) {
                failing = true;
                lastWarnMillis = now;
                if (cause != null) {
                    logger.warn(format, what, reason, cause);
                } else {
                    logger.warn(format, what, reason);
                }
            } else if (cause != null) {
                logger.debug(format, what, reason, cause);
            } else {
                logger.debug(format, what, reason);
            }
        }

        void succeeded() {
            if (!failing) {
                return;
            }
            synchronized (this) {
                if (failing) {
                    failing = false;
                    logger.info("Reading {} recovered, cross-site checks are effective again", what);
                }
            }
        }

        boolean isFailing() {
            return failing;
        }
    }
}
