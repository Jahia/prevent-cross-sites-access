package org.jahia.community.modules;

import org.jahia.exceptions.JahiaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private AtomicReference<Supplier<Collection<String>>> sites;
    private CrossSitesFilter filter;

    @BeforeEach
    void setUp() {
        hostMap = new HashMap<>();
        hostMap.put("sitea.example", "siteA");
        hostMap.put("siteb.example", "siteB");
        sites = new AtomicReference<>(() -> Arrays.asList("siteA", "siteB", "systemsite", "acme.fr"));
        filter = new CrossSitesFilter(() -> sites.get().get(), host -> hostMap.get(host), logger);
        when(request.getServerName()).thenReturn("siteb.example");
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
    @DisplayName("a block logs one INFO line naming both sites and the decoded path")
    void doFilter_foreignSite_logsOneInfoLine() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        verify(logger, times(1)).info(contains("siteA"));
        verify(logger).info(contains("siteB"));
        verify(logger).info(contains("/cms/render/live/en/sites/siteA/home.html"));
    }

    @Test
    @DisplayName("control characters in the decoded path cannot forge log lines")
    void doFilter_blockedPathWithNewline_isSanitisedInLog() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/a\r\nFORGED");
        run();
        verify(logger).info(org.mockito.ArgumentMatchers.argThat((String m) -> !m.contains("\n") && !m.contains("\r")));
    }

    @Test
    @DisplayName("unknown site key passes")
    void doFilter_unknownKey_passes() throws Exception {
        path("/cms/render/live", "/en/sites/ghost/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("already-encoded key (decoded once) is not a site and passes this filter")
    void doFilter_percentEncodedKey_isNotASite() throws Exception {
        path("/en", "/sites/%64igitall/home.html");
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
    @DisplayName("unmapped hostname passes (documented fail-open)")
    void doFilter_unmappedHost_passes() throws Exception {
        when(request.getServerName()).thenReturn("unknown.example");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "", "..."})
    @DisplayName("localhost and empty hostnames pass without any lookup")
    void doFilter_localhostOrEmpty_passesWithoutLookup(String host) throws Exception {
        when(request.getServerName()).thenReturn(host);
        hostMap.put(host, "siteB");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("null server name passes")
    void doFilter_nullServerName_passes() throws Exception {
        when(request.getServerName()).thenReturn(null);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"siteb.example.", "siteb.example..."})
    @DisplayName("trailing-dot hostname is blocked like the plain hostname")
    void doFilter_trailingDotHost_blocked(String host) throws Exception {
        when(request.getServerName()).thenReturn(host);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("hostname case is passed through to the case-insensitive lookup untouched")
    void doFilter_upperCaseHost_passedThroughToLookup() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        CrossSitesFilter f = new CrossSitesFilter(() -> Arrays.asList("siteA", "siteB"), host -> {
            seen.set(host);
            return "siteB";
        }, logger);
        when(request.getServerName()).thenReturn("SiteB.Example.");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        f.doFilter(request, response, chain);
        assertThat(seen.get()).isEqualTo("SiteB.Example");
        verify(response).sendError(404);
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
        int i = fullPath.indexOf("/default");
        path(fullPath.substring(0, i + "/default".length()), fullPath.substring(i + "/default".length()));
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
    @DisplayName("null servletPath and pathInfo pass")
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
    @DisplayName("site-list exception lets the request through and logs WARN once across repeated failures")
    void doFilter_siteListThrows_passesAndWarnsOnce() throws Exception {
        sites.set(() -> {
            throw new IllegalStateException("repo down");
        });
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
    @DisplayName("site-list recovery logs INFO once and re-arms the WARN")
    void doFilter_siteListRecovers_infoOnceThenWarnsAgain() throws Exception {
        Supplier<Collection<String>> failing = () -> {
            throw new IllegalStateException("repo down");
        };
        Supplier<Collection<String>> healthy = () -> Collections.singletonList("siteB");
        path("/cms/render/live", "/en/sites/siteB/home.html");

        sites.set(failing);
        run();
        sites.set(healthy);
        run();
        run();
        assertThat(filter.isSiteListFailing()).isFalse();
        verify(logger, times(1)).info(contains("recover"));

        sites.set(failing);
        run();
        verify(logger, times(2)).warn(anyString(), any(Throwable.class));
    }

    @Test
    @DisplayName("null site list passes and counts as a failure")
    void doFilter_siteListNull_passes() throws Exception {
        sites.set(() -> null);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        run();
        assertPassed2();
        verify(logger, times(1)).warn(anyString());
        assertThat(filter.isSiteListFailing()).isTrue();
    }

    private void assertPassed2() throws Exception {
        verify(chain, times(2)).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
    }

    @Test
    @DisplayName("empty site list passes quietly")
    void doFilter_siteListEmpty_passesQuietly() throws Exception {
        sites.set(Collections::emptyList);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        verify(logger, never()).warn(anyString());
        verify(logger, never()).warn(anyString(), any(Throwable.class));
        assertThat(filter.isSiteListFailing()).isFalse();
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
        }, logger);
        path("/cms/render/live", "/en/sites/siteA/home.html");

        f.doFilter(request, response, chain);
        f.doFilter(request, response, chain);
        verify(chain, times(2)).doFilter(request, response);
        verify(logger, times(1)).warn(anyString(), any(Throwable.class));
        assertThat(f.isHostLookupFailing()).isTrue();

        broken.set(false);
        f.doFilter(request, response, chain);
        verify(logger).info(contains("recover"));
        verify(response).sendError(404);
        assertThat(f.isHostLookupFailing()).isFalse();
    }

    @Test
    @DisplayName("lookup returning null means no site and passes")
    void doFilter_lookupReturnsNull_passes() throws Exception {
        hostMap.clear();
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        verify(logger, never()).warn(anyString(), any(Throwable.class));
        verify(logger, never()).info(eq("x"));
    }
}
