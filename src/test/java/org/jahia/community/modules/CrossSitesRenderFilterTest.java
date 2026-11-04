package org.jahia.community.modules;

import org.jahia.exceptions.JahiaException;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The render-chain layer: it judges what Jahia actually resolved (the main resource's node and the
 * render context's site), so it needs no copy of Jahia's URL or parameter parsing.
 */
@ExtendWith(MockitoExtension.class)
class CrossSitesRenderFilterTest {

    @Mock
    private RenderContext renderContext;
    @Mock
    private Resource resource;
    @Mock
    private JCRNodeWrapper node;
    @Mock
    private HttpServletRequest request;
    @Mock
    private Logger logger;

    private Map<String, String> hostMap;
    private List<String> lookedUpHosts;
    private AtomicReference<Supplier<Collection<String>>> sites;
    private CrossSitesRenderFilter filter;

    @BeforeEach
    void setUp() {
        hostMap = new HashMap<>();
        hostMap.put("siteb.example", "siteB");
        lookedUpHosts = new ArrayList<>();
        sites = new AtomicReference<>(() -> Arrays.asList("siteA", "siteB", "systemsite"));
        filter = new CrossSitesRenderFilter(new SiteDirectory(() -> sites.get().get(), host -> {
            lookedUpHosts.add(host);
            return hostMap.get(host);
        }, logger, new AtomicLong(1_000)::get), logger, new AtomicLong(1_000)::get);
        lenient().when(renderContext.getRequest()).thenReturn(request);
        lenient().when(request.getServerName()).thenReturn("siteb.example");
        lenient().when(resource.getNode()).thenReturn(node);
        lenient().when(logger.isInfoEnabled()).thenReturn(true);
        lenient().when(logger.isDebugEnabled()).thenReturn(true);
    }

    private static JCRSiteNode site(String key) {
        final JCRSiteNode site = mock(JCRSiteNode.class);
        lenient().when(site.getSiteKey()).thenReturn(key);
        return site;
    }

    /** The main resource's node at `path`, resolving to site `nodeSite`, rendered with context site `contextSite`. */
    private void resolved(String path, String nodeSite, String contextSite) throws RepositoryException {
        lenient().when(node.getPath()).thenReturn(path);
        lenient().when(resource.getNodePath()).thenReturn(path);
        // Built before stubbing: creating a mock inside thenReturn() is an unfinished stubbing.
        final JCRSiteNode resolveSite = nodeSite == null ? null : site(nodeSite);
        final JCRSiteNode renderSite = contextSite == null ? null : site(contextSite);
        lenient().when(node.getResolveSite()).thenReturn(resolveSite);
        lenient().when(renderContext.getSite()).thenReturn(renderSite);
    }

    private void assertPassed() throws Exception {
        assertThat(filter.prepare(renderContext, resource, null)).isNull();
        verify(request, never()).setAttribute(eq("siteKey"), any());
    }

    private void assertBlocked() {
        assertThatThrownBy(() -> filter.prepare(renderContext, resource, null)).isInstanceOf(PathNotFoundException.class);
        verify(request).setAttribute("siteKey", "siteB");
    }

    private List<String> logged(String level) {
        return mockingDetails(logger).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(level))
                .map(Invocation::getArguments)
                .map(args -> MessageFormatter.arrayFormat((String) args[0], Arrays.copyOfRange(args, 1, args.length)).getMessage())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("runs early in the chain, before the caching filters (priority 16), in live mode, on the main resource only")
    void registration_liveMainResourceOnly() {
        assertThat(filter.getPriority()).isLessThan(16f).isEqualTo(CrossSitesRenderFilter.PRIORITY);
        final Resource main = mock(Resource.class);
        when(main.getNodePath()).thenReturn("/sites/siteB/home");
        when(renderContext.getMainResource()).thenReturn(main);
        when(resource.getNodePath()).thenReturn("/sites/siteB/home");
        when(renderContext.getMode()).thenReturn("live");
        assertThat(filter.areConditionsMatched(renderContext, resource)).isTrue();

        when(renderContext.getMode()).thenReturn("preview");
        assertThat(filter.areConditionsMatched(renderContext, resource)).isFalse();

        when(renderContext.getMode()).thenReturn("live");
        when(resource.getNodePath()).thenReturn("/sites/siteA/contents/reference");
        assertThat(filter.areConditionsMatched(renderContext, resource)).isFalse();
    }

    @Test
    @DisplayName("activation logs one INFO line naming the layer and its priority")
    void activate_logsPriority() {
        filter.activate();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("render") && m.contains(String.valueOf(CrossSitesRenderFilter.PRIORITY)));
    }

    @Test
    @DisplayName("the hostname's own page passes")
    void prepare_ownPage_passes() throws Exception {
        resolved("/sites/siteB/home", "siteB", "siteB");
        assertPassed();
    }

    @Test
    @DisplayName("a main resource of another existing site is refused with 404 and the hostname's site as siteKey")
    void prepare_foreignMainResource_blocked() throws Exception {
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("render layer") && m.contains("site 'siteA'")
                && m.contains("hostname 'siteb.example' maps to site 'siteB'") && m.contains("/sites/siteA/home")
                && m.endsWith("referer: -"));
    }

    @Test
    @DisplayName("siteKey is set before the refusal is thrown, so ErrorServlet renders the hostname's error page")
    void prepare_blocked_setsSiteKeyFirst() throws Exception {
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertBlocked();
        InOrder order = inOrder(logger, request);
        order.verify(logger).info(any(String.class), any(Object[].class));
        order.verify(request).setAttribute("siteKey", "siteB");
    }

    @Test
    @DisplayName("the own page rendered with another site as render-context site (jsite) is refused")
    void prepare_foreignRenderContextSite_blocked() throws Exception {
        resolved("/sites/siteB/home", "siteB", "siteA");
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("render-context site 'siteA'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/sites", "/"})
    @DisplayName("the /sites node and the repository root, which list every site, are refused")
    void prepare_sitesRootOrRepositoryRoot_blocked(String path) throws Exception {
        resolved(path, "systemsite", "siteB");
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("every site"));
    }

    @Test
    @DisplayName("systemsite content passes on any mapped hostname")
    void prepare_systemsite_passes() throws Exception {
        resolved("/sites/systemsite/files/shared.txt", "systemsite", "siteB");
        assertPassed();
    }

    @Test
    @DisplayName("a module node (its resolve site is the module, not a site) and a node outside /sites pass")
    void prepare_moduleOrUserNode_passes() throws Exception {
        resolved("/modules/dx-base-demo-templates/1.0/templates", "dx-base-demo-templates", "siteB");
        assertPassed();
    }

    @Test
    @DisplayName("a resolve site that is no existing site key passes")
    void prepare_unknownSite_passes() throws Exception {
        resolved("/sites/ghost/home", "ghost", "siteB");
        assertPassed();
    }

    @Test
    @DisplayName("no resolve site and no render-context site pass")
    void prepare_noSites_passes() throws Exception {
        resolved("/users/root", null, null);
        assertPassed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown.example", "localhost", ""})
    @DisplayName("an unmapped, localhost or empty hostname stands the layer down")
    void prepare_unmappedHost_passes(String host) throws Exception {
        when(request.getServerName()).thenReturn(host);
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertPassed();
    }

    @Test
    @DisplayName("trailing dots of the hostname are ignored, as in the servlet layer")
    void prepare_trailingDotHost_blocked() throws Exception {
        when(request.getServerName()).thenReturn("siteb.example.");
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertBlocked();
        assertThat(lookedUpHosts).containsExactly("siteb.example");
    }

    @Test
    @DisplayName("a failing hostname lookup lets the page through with one WARN")
    void prepare_hostLookupFails_failsOpen() throws Exception {
        filter = new CrossSitesRenderFilter(new SiteDirectory(() -> sites.get().get(), host -> {
            throw new JahiaException("x", "y", JahiaException.ERROR_SEVERITY, JahiaException.DATABASE_ERROR);
        }, logger, new AtomicLong(1_000)::get), logger, new AtomicLong(1_000)::get);
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
    }

    @Test
    @DisplayName("an empty site list lets the page through with one WARN")
    void prepare_emptySiteList_failsOpen() throws Exception {
        sites.set(java.util.Collections::emptyList);
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable resolve site still checks the render-context site, and WARNs once")
    void prepare_resolveSiteFails_checksContextSite() throws Exception {
        resolved("/sites/siteA/home", "siteA", "siteA");
        when(node.getResolveSite()).thenThrow(new RepositoryException("repo down"));
        assertBlocked();
        assertThat(logged("warn")).hasSize(1);
        assertThat(filter.isResolvedSiteFailing()).isTrue();
    }

    @Test
    @DisplayName("an unreadable resolve site with the own render-context site passes")
    void prepare_resolveSiteFailsOwnContext_passes() throws Exception {
        resolved("/sites/siteA/home", "siteA", "siteB");
        when(node.getResolveSite()).thenThrow(new RepositoryException("repo down"));
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
    }

    @Test
    @DisplayName("nothing is enforced while Jahia renders an error page")
    void prepare_errorDispatch_passes() throws Exception {
        when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
        resolved("/sites/siteA/home", "siteA", "siteA");
        assertPassed();
        assertThat(lookedUpHosts).isEmpty();
    }

    @Test
    @DisplayName("a render without a request (background rendering) passes")
    void prepare_noRequest_passes() throws Exception {
        when(renderContext.getRequest()).thenReturn(null);
        assertThat(filter.prepare(renderContext, resource, null)).isNull();
    }

    @Test
    @DisplayName("the hostname, node path and referer are sanitised and capped in the log")
    void prepare_hostileValues_sanitisedInLog() throws Exception {
        final String host = "si\r\nteb‮.example";
        when(request.getServerName()).thenReturn(host);
        hostMap.put(host, "siteB");
        when(request.getHeader("Referer")).thenReturn("http://x/\r\n" + "r".repeat(600));
        resolved("/sites/siteA/a b" + "p".repeat(600), "siteA", "siteA");
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> !m.contains("\n") && !m.contains("\r")
                && !m.contains("‮") && !m.contains(" ") && !m.contains("p".repeat(513)) && !m.contains("r".repeat(513)));
    }

    // --- jarea: Jahia's TemplateAjaxFilter renders the node this UUID names inside the page ---------

    private static final String AREA_ID = "4c1d4f0e-0000-4000-8000-000000000001";

    /** The page is the main resource; `jarea` names a node at `areaPath` (null: no such node). */
    private void pageWithArea(String pagePath, String areaPath) throws RepositoryException {
        resolved(pagePath, pagePath.startsWith("/sites/siteA") ? "siteA" : "siteB", pagePath.startsWith("/sites/siteA") ? "siteA" : "siteB");
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        lenient().when(node.getSession()).thenReturn(session);
        lenient().when(request.getParameter("jarea")).thenReturn(AREA_ID);
        if (areaPath == null) {
            lenient().when(session.getNodeByUUID(AREA_ID)).thenThrow(new ItemNotFoundException(AREA_ID));
        } else {
            final JCRNodeWrapper area = mock(JCRNodeWrapper.class);
            lenient().when(area.getPath()).thenReturn(areaPath);
            lenient().when(session.getNodeByUUID(AREA_ID)).thenReturn(area);
        }
    }

    @Test
    @DisplayName("jarea naming another site's node on the own page is refused, with the hostname's site as siteKey")
    void prepare_jareaForeignNode_blocked() throws Exception {
        pageWithArea("/sites/siteB/home", "/sites/siteA/home/area-main/events");
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("render layer") && m.contains("jarea")
                && m.contains("site 'siteA'") && m.contains("/sites/siteA/home/area-main/events"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown.example", "localhost", ""})
    @DisplayName("jarea naming another site's node is refused on an unmapped hostname too: Jahia would cache it under the page")
    void prepare_jareaForeignNodeUnmappedHost_blocked(String host) throws Exception {
        when(request.getServerName()).thenReturn(host);
        pageWithArea("/sites/siteB/home", "/sites/siteA/home/area-main/events");
        assertThatThrownBy(() -> filter.prepare(renderContext, resource, null)).isInstanceOf(PathNotFoundException.class);
        // No site behind the hostname: the error page is the addressed page's site.
        verify(request).setAttribute("siteKey", "siteB");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/sites", "/"})
    @DisplayName("jarea naming the /sites node or the repository root, which list every site, is refused")
    void prepare_jareaSitesRoot_blocked(String areaPath) throws Exception {
        pageWithArea("/sites/siteB/home", areaPath);
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("every site"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/sites/siteB/home/area-main", "/sites/siteB", "/sites/systemsite/contents/shared",
            "/modules/dx-base-demo-templates/1.0/templates/base/area-main"})
    @DisplayName("jarea naming a node of the page's own site, systemsite or a template passes")
    void prepare_jareaOwnSystemOrTemplateNode_passes(String areaPath) throws Exception {
        pageWithArea("/sites/siteB/home", areaPath);
        assertPassed();
        assertThat(logged("warn")).isEmpty();
    }

    @Test
    @DisplayName("jarea naming no readable node passes without a WARN: Jahia then fails to render it as well")
    void prepare_jareaUnknownNode_passes() throws Exception {
        pageWithArea("/sites/siteB/home", null);
        assertPassed();
        assertThat(logged("warn")).isEmpty();
    }

    @Test
    @DisplayName("a failing jarea lookup lets the page through with one WARN")
    void prepare_jareaLookupFails_failsOpen() throws Exception {
        pageWithArea("/sites/siteB/home", "/sites/siteB/home/area-main");
        when(node.getSession()).thenThrow(new RepositoryException("repo down"));
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("a blank jarea is no area and costs no lookup")
    void prepare_jareaBlank_passes(String value) throws Exception {
        resolved("/sites/siteB/home", "siteB", "siteB");
        when(request.getParameter("jarea")).thenReturn(value);
        assertPassed();
        verify(node, never()).getSession();
    }
}
