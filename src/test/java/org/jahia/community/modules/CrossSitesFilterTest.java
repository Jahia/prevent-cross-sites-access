package org.jahia.community.modules;

import org.jahia.exceptions.JahiaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossSitesFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;
    @Mock
    private Logger logger;

    private Map<String, String> hostMap;
    private List<String> lookedUpHosts;
    private AtomicLong clockMillis;
    private AtomicReference<Supplier<Collection<String>>> sites;
    private CrossSitesFilter filter;

    @BeforeEach
    void setUp() {
        hostMap = new HashMap<>();
        hostMap.put("sitea.example", "siteA");
        hostMap.put("siteb.example", "siteB");
        lookedUpHosts = new ArrayList<>();
        clockMillis = new AtomicLong(1_000);
        sites = new AtomicReference<>(() -> Arrays.asList("siteA", "siteB", "digitall", "systemsite", "acme.fr"));
        filter = new CrossSitesFilter(() -> sites.get().get(), host -> {
            lookedUpHosts.add(host);
            return hostMap.get(host);
        }, logger, clockMillis::get);
        // Not every decision reaches the hostname, so this one stub is allowed to go unused.
        lenient().when(request.getServerName()).thenReturn("siteb.example");
    }

    private void path(String servletPath, String pathInfo) {
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
    }

    private void run() throws Exception {
        filter.doFilter(request, response, chain);
    }

    private void assertPassed() throws Exception {
        verify(chain).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
    }

    private void assertBlocked() throws Exception {
        verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
        verify(chain, never()).doFilter(any(), any());
    }

    private static Supplier<Collection<String>> throwing() {
        return () -> {
            throw new IllegalStateException("repo down");
        };
    }

    @Test
    @DisplayName("registers for REQUEST and FORWARD dispatches")
    void constructor_registersRequestAndForward() {
        assertThat(filter.getDispatcherTypes()).isEqualTo(Set.of("REQUEST", "FORWARD"));
        assertThat(new CrossSitesFilter().getDispatcherTypes()).isEqualTo(Set.of("REQUEST", "FORWARD"));
        assertThat(filter.getOrder()).isEqualTo(0.5f);
        assertThat(filter.isMatchAllUrls()).isTrue();
    }

    @Test
    @DisplayName("own site on own hostname passes")
    void doFilter_ownSite_passes() throws Exception {
        path("/cms/render/live", "/en/sites/siteB/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("foreign existing site on a mapped hostname is answered 404 without calling the chain")
    void doFilter_foreignSite_blocked() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("a block logs one INFO line naming both sites, the hostname and the decoded path")
    void doFilter_foreignSite_logsOneInfoLine() throws Exception {
        when(request.getServerName()).thenReturn("SiteB.Example.");
        hostMap.put("SiteB.Example", "siteB");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
        verify(logger, times(1)).info(anyString());
        verify(logger).info(ArgumentMatchers.argThat((String m) -> m.contains("siteA") && m.contains("siteB")
                && m.contains("SiteB.Example") && !m.contains("SiteB.Example.")
                && m.contains("/cms/render/live/en/sites/siteA/home.html")));
    }

    @Test
    @DisplayName("line separators, including NEL and U+2028/U+2029, cannot forge log lines")
    void doFilter_blockedPathWithLineSeparators_isSanitisedInLog() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/a\r\nb\u0085c d e");
        run();
        assertBlocked();
        verify(logger).info(ArgumentMatchers.argThat((String m) -> m.chars().noneMatch(
                c -> c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029)));
    }

    @Test
    @DisplayName("the logged path is capped at 512 characters plus an ellipsis")
    void doFilter_blockedLongPath_isTruncatedInLog() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/" + "a".repeat(600));
        run();
        assertBlocked();
        verify(logger).info(ArgumentMatchers.argThat((String m) -> m.endsWith("a…")
                && !m.contains("a".repeat(513))
                && m.contains("a".repeat(480))));
    }

    @Test
    @DisplayName("unknown site key passes")
    void doFilter_unknownKey_passes() throws Exception {
        path("/cms/render/live", "/en/sites/ghost/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("percent sign in the key is not decoded here: an existing site named digitall is not addressed")
    void doFilter_percentEncodedKey_isNotASite() throws Exception {
        path("/en", "/sites/%64igitall/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("site key case matters: DIGITALL is not digitall")
    void doFilter_keyInOtherCase_passes() throws Exception {
        path("/cms/render/live", "/en/sites/DIGITALL/home.html");
        run();
        assertPassed();
    }

    @ParameterizedTest(name = "[{index}] {0}{1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "/cms/render/live,/en/sites/{}digitall/home.html",
            "/cms/render/live,/en/{}sites/digitall/home.html",
            "/en,/sites/{}digitall/home.html",
            "/en,/{}sites/digitall/home.html",
            "/files/live,/sites/{}digitall/files/a.png",
            "/cms/render/{}default,/en/sites/digitall/home.html",
            "/cms/{}render/{}live,/en/sites/{}digitall.html"
    })
    @DisplayName("JCR's {} empty-namespace marker cannot hide a foreign site")
    void doFilter_emptyNamespaceMarker_blocked(String servletPath, String pathInfo) throws Exception {
        path(servletPath, pathInfo);
        run();
        assertBlocked();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/sites/{}{}digitall/home.html", "/sites/{ }digitall/home.html", "/sites/:digitall/home.html"})
    @DisplayName("forms JCR does not accept as the site are not normalised further")
    void doFilter_otherMarkerForms_pass(String pathInfo) throws Exception {
        path("/en", pathInfo);
        run();
        assertPassed();
    }

    @Test
    @DisplayName("systemsite passes from any hostname")
    void doFilter_systemSite_passes() throws Exception {
        path("/cms/render/live", "/en/sites/systemsite/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("unmapped hostname passes and the fail-open is logged at DEBUG with the normalised host")
    void doFilter_unmappedHost_passesAndLogsDebug() throws Exception {
        when(request.getServerName()).thenReturn("unknown.example.");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        verify(logger).debug(contains("unknown.example"));
        verify(logger, never()).info(anyString());
    }

    @Test
    @DisplayName("127.0.0.1 is an ordinary host: the lookup decides")
    void doFilter_ipAddress_lookupDecides() throws Exception {
        when(request.getServerName()).thenReturn("127.0.0.1");
        hostMap.put("127.0.0.1", "siteB");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
        assertThat(lookedUpHosts).containsExactly("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "localhost.", "", "..."})
    @DisplayName("localhost and empty hostnames pass without any lookup")
    void doFilter_localhostOrEmpty_passesWithoutLookup(String host) throws Exception {
        when(request.getServerName()).thenReturn(host);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        assertThat(lookedUpHosts).isEmpty();
    }

    @Test
    @DisplayName("null server name passes without any lookup")
    void doFilter_nullServerName_passes() throws Exception {
        when(request.getServerName()).thenReturn(null);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        assertThat(lookedUpHosts).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"siteb.example.", "siteb.example..."})
    @DisplayName("trailing-dot hostname is blocked like the plain hostname")
    void doFilter_trailingDotHost_blocked(String host) throws Exception {
        when(request.getServerName()).thenReturn(host);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
        assertThat(lookedUpHosts).containsExactly("siteb.example");
    }

    @Test
    @DisplayName("hostname case is passed through to the case-insensitive lookup untouched")
    void doFilter_upperCaseHost_passedThroughToLookup() throws Exception {
        when(request.getServerName()).thenReturn("SiteB.Example.");
        hostMap.put("SiteB.Example", "siteB");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
        assertThat(lookedUpHosts).containsExactly("SiteB.Example");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/cms/edit/default/en/sites/siteA/home.html",
            "/cms/editframe/default/en/sites/siteA/home.html",
            "/cms/render/default/en/sites/siteA/home.html",
            "/files/default/sites/siteA/files/a.png"
    })
    @DisplayName("authoring paths pass even for a foreign site")
    void doFilter_authoringPath_passes(String fullPath) throws Exception {
        int i = fullPath.indexOf("/default") + "/default".length();
        path(fullPath.substring(0, i), fullPath.substring(i));
        run();
        assertPassed();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/cms/render/live/en/sites/siteA/home.html",
            "/files/live/sites/siteA/files/a.png",
            "/en/sites/siteA/home.html"
    })
    @DisplayName("live paths and the SEO short form are enforced")
    void doFilter_livePath_blocked(String fullPath) throws Exception {
        path(fullPath, null);
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("FORWARD target path (servletPath only) is evaluated")
    void doFilter_forwardTargetServletPathOnly_blocked() throws Exception {
        path("/cms/render/live/en/sites/siteA/home.html", null);
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("null servletPath and pathInfo pass quietly")
    void doFilter_nullPaths_passes() throws Exception {
        path(null, null);
        run();
        assertPassed();
        verifyNoInteractions(logger);
    }

    @Test
    @DisplayName("null servletPath with a pathInfo is still evaluated")
    void doFilter_nullServletPath_usesPathInfo() throws Exception {
        path(null, "/sites/siteA/home.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("dotted site key is resolved against the real list")
    void doFilter_dottedSiteKey_blocked() throws Exception {
        path("/cms/render/live", "/sites/acme.fr.default.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("does not enforce while Jahia renders an error page")
    void doFilter_errorDispatch_notEnforced() throws Exception {
        when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
        path("/errors/sites/siteA", "/error.html");
        run();
        assertPassed();
        assertThat(lookedUpHosts).isEmpty();
    }

    @Test
    @DisplayName("the same path is enforced when no error status is present")
    void doFilter_sameSiteLevelErrorPathWithoutErrorStatus_blocked() throws Exception {
        when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(null);
        path("/errors/sites/siteA", "/error.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("site-list exception passes, WARN once across repeated failures, DEBUG for the rest")
    void doFilter_siteListThrows_passesAndWarnsOnce() throws Exception {
        sites.set(throwing());
        path("/cms/render/live", "/en/sites/siteA/home.html");
        for (int i = 0; i < 3; i++) {
            run();
        }
        verify(chain, times(3)).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
        verify(logger, times(1)).warn(anyString(), any(Throwable.class));
        verify(logger, times(2)).debug(anyString(), any(Throwable.class));
        assertThat(filter.isSiteListFailing()).isTrue();
    }

    @Test
    @DisplayName("a long outage warns again at most every ten minutes")
    void doFilter_siteListKeepsFailing_rewarnsEveryTenMinutes() throws Exception {
        sites.set(throwing());
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        clockMillis.addAndGet(TimeUnit.MINUTES.toMillis(10) - 1);
        run();
        verify(logger, times(1)).warn(anyString(), any(Throwable.class));
        clockMillis.addAndGet(1);
        run();
        verify(logger, times(2)).warn(anyString(), any(Throwable.class));
        run();
        verify(logger, times(2)).warn(anyString(), any(Throwable.class));
        verify(logger, times(2)).debug(anyString(), any(Throwable.class));
    }

    @Test
    @DisplayName("site-list recovery logs INFO once and re-arms the WARN")
    void doFilter_siteListRecovers_infoOnceThenWarnsAgain() throws Exception {
        Supplier<Collection<String>> healthy = () -> Collections.singletonList("siteB");
        path("/cms/render/live", "/en/sites/siteB/home.html");

        sites.set(throwing());
        run();
        sites.set(healthy);
        run();
        run();
        assertThat(filter.isSiteListFailing()).isFalse();
        verify(logger, times(1)).info(contains("recovered"));

        sites.set(throwing());
        run();
        verify(logger, times(2)).warn(anyString(), any(Throwable.class));
    }

    @Test
    @DisplayName("null site list passes and counts as a failure")
    void doFilter_siteListNull_passes() throws Exception {
        sites.set(() -> null);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        verify(logger, times(1)).warn(anyString());
        assertThat(filter.isSiteListFailing()).isTrue();
    }

    @Test
    @DisplayName("empty site list passes and counts as a failure (Jahia always has systemsite)")
    void doFilter_siteListEmpty_passesAndWarnsOnce() throws Exception {
        sites.set(Collections::emptyList);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        verify(chain).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
        verify(logger, times(1)).warn(anyString());
        assertThat(filter.isSiteListFailing()).isTrue();
    }

    @Test
    @DisplayName("host-lookup failure passes, WARN once, INFO on recovery")
    void doFilter_hostLookupThrows_passesWarnsOnceThenRecovers() throws Exception {
        AtomicReference<Boolean> broken = new AtomicReference<>(true);
        CrossSitesFilter f = new CrossSitesFilter(() -> Arrays.asList("siteA", "siteB"), host -> {
            if (broken.get()) {
                throw new JahiaException("x", "y", JahiaException.ERROR_SEVERITY, JahiaException.DATABASE_ERROR);
            }
            return "siteB";
        }, logger, clockMillis::get);
        path("/cms/render/live", "/en/sites/siteA/home.html");

        f.doFilter(request, response, chain);
        f.doFilter(request, response, chain);
        verify(chain, times(2)).doFilter(request, response);
        verify(logger, times(1)).warn(anyString(), any(Throwable.class));
        assertThat(f.isHostLookupFailing()).isTrue();

        broken.set(false);
        f.doFilter(request, response, chain);
        verify(logger).info(contains("recovered"));
        assertThat(f.isHostLookupFailing()).isFalse();
        // the recovered request is blocked: sendError, and the chain was not called a third time
        verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    @DisplayName("lookup returning null means no site and passes without any warning")
    void doFilter_lookupReturnsNull_passes() throws Exception {
        hostMap.clear();
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        verify(logger, never()).warn(anyString(), any(Throwable.class));
        verify(logger, never()).warn(anyString());
        verify(logger, never()).info(anyString());
    }
}
