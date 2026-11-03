package org.jahia.community.modules;

import org.jahia.exceptions.JahiaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.invocation.Invocation;
import org.slf4j.helpers.MessageFormatter;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
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
        }, siteKey -> null, logger, clockMillis::get);
        // Not every decision reaches the hostname, so this one stub is allowed to go unused.
        lenient().when(request.getServerName()).thenReturn("siteb.example");
        lenient().when(logger.isInfoEnabled()).thenReturn(true);
        lenient().when(logger.isDebugEnabled()).thenReturn(true);
    }

    /** Formatted messages (placeholders filled in) of every call made on the mocked logger at that level. */
    private List<String> logged(String level) {
        return loggerCalls(level).stream().map(CrossSitesFilterTest::format).collect(Collectors.toList());
    }

    /** Same, restricted to the calls that passed an exception as the last argument. */
    private List<String> withThrowable(String level) {
        return loggerCalls(level).stream()
                .filter(args -> args[args.length - 1] instanceof Throwable)
                .map(CrossSitesFilterTest::format).collect(Collectors.toList());
    }

    private List<Object[]> loggerCalls(String level) {
        return mockingDetails(logger).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(level))
                .map(Invocation::getArguments)
                .collect(Collectors.toList());
    }

    private static String format(Object[] args) {
        return MessageFormatter.arrayFormat((String) args[0], Arrays.copyOfRange(args, 1, args.length)).getMessage();
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

    private static Stream<Arguments> passingRequests() {
        return Stream.of(
                Arguments.of(Named.of("own site on own hostname", "/cms/render/live"), "/en/sites/siteB/home.html"),
                Arguments.of(Named.of("unknown site key", "/cms/render/live"), "/en/sites/ghost/home.html"),
                Arguments.of(Named.of("percent sign in key is not decoded here, digitall is not addressed", "/en"),
                        "/sites/%64igitall/home.html"),
                Arguments.of(Named.of("site key case matters: DIGITALL is not digitall", "/cms/render/live"),
                        "/en/sites/DIGITALL/home.html"),
                Arguments.of(Named.of("systemsite from any hostname", "/cms/render/live"),
                        "/en/sites/systemsite/home.html"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("passingRequests")
    @DisplayName("requests that address no foreign existing site pass")
    void doFilter_noForeignSite_passes(String servletPath, String pathInfo) throws Exception {
        path(servletPath, pathInfo);
        run();
        assertPassed();
    }

    private static Stream<Arguments> blockedRequests() {
        return Stream.of(
                Arguments.of(Named.of("foreign existing site", "/cms/render/live"), "/en/sites/siteA/home.html"),
                Arguments.of(Named.of("FORWARD target path (servletPath only)",
                        "/cms/render/live/en/sites/siteA/home.html"), null),
                Arguments.of(Named.of("null servletPath, pathInfo is evaluated", null), "/sites/siteA/home.html"),
                Arguments.of(Named.of("dotted site key resolved against the real list", "/cms/render/live"),
                        "/sites/acme.fr.default.html"),
                // JCR's {} empty-namespace marker must not hide a foreign site (measured bypass on 8.2.3.7).
                Arguments.of(Named.of("{} in the site key", "/cms/render/live"), "/en/sites/{}digitall/home.html"),
                Arguments.of(Named.of("{} in the sites segment", "/cms/render/live"), "/en/{}sites/digitall/home.html"),
                Arguments.of(Named.of("{} in the SEO short form site key", "/en"), "/sites/{}digitall/home.html"),
                Arguments.of(Named.of("{} in the SEO short form sites segment", "/en"), "/{}sites/digitall/home.html"),
                Arguments.of(Named.of("{} in a live file URL", "/files/live"), "/sites/{}digitall/files/a.png"),
                Arguments.of(Named.of("{}default is not an authoring workspace", "/cms/render/{}default"),
                        "/en/sites/digitall/home.html"),
                Arguments.of(Named.of("{} on every segment", "/cms/{}render/{}live"), "/en/sites/{}digitall.html"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("blockedRequests")
    @DisplayName("foreign existing site on a mapped hostname is answered 404 without calling the chain")
    void doFilter_foreignSite_blocked(String servletPath, String pathInfo) throws Exception {
        path(servletPath, pathInfo);
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
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("siteA") && m.contains("siteB")
                && m.contains("SiteB.Example") && !m.contains("SiteB.Example.")
                && m.contains("/cms/render/live/en/sites/siteA/home.html"));
    }

    @Test
    @DisplayName("a block sets the request siteKey to the hostname's site, before sendError")
    void doFilter_blocked_setsHostSiteKeyBeforeSendError() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
        InOrder order = inOrder(request, response);
        order.verify(request).setAttribute("siteKey", "siteB");
        order.verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("a pass never sets the request siteKey")
    void doFilter_passed_doesNotSetSiteKey() throws Exception {
        path("/cms/render/live", "/en/sites/siteB/home.html");
        run();
        assertPassed();
        verify(request, never()).setAttribute(eq("siteKey"), any());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/cms/render/live/en/sites/siteA.html/",
            "/cms/render/live/en/sites/siteA.html/*",
            "/cms/render/live/en/sites/siteA.default.html/",
            "/cms/render/live/en/sites/siteA/*",
            "/cms/render/live/en/sites/{}acme.fr.default.html",
            "/cms/render/live/en/sites/{}acme.fr.default.html/",
            // plain live paths and the SEO short form
            "/cms/render/live/en/sites/siteA/home.html",
            "/files/live/sites/siteA/files/a.png",
            "/en/sites/siteA/home.html"
    })
    @DisplayName("live paths, the SEO short form, renderer suffixes (trailing slash, /*) and {} are enforced")
    void doFilter_rendererSuffixes_blocked(String fullPath) throws Exception {
        path(fullPath, null);
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("the CKEditor {workspace} placeholder is exempt like the default workspace")
    void doFilter_workspacePlaceholder_passes() throws Exception {
        path("/files/{workspace}", "/sites/siteA/files/a.png");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("bidi overrides and zero-width characters in the path cannot reach the log")
    void doFilter_blockedPathWithFormatChars_isSanitisedInLog() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/a\u202Eb\u2066c\u2069d\u200Be\u202A");
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.chars().noneMatch(
                c -> Character.getType(c) == Character.FORMAT));
    }

    @Test
    @DisplayName("control characters, NEL and bidi overrides in the hostname cannot reach the log")
    void doFilter_blockedHostWithUnsafeChars_isSanitisedInLog() throws Exception {
        String host = "si\r\nteb\u0085.ex\u202Eample\u2028";
        when(request.getServerName()).thenReturn(host);
        hostMap.put(host, "siteB");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("si??teb?.ex?ample?")
                && m.chars().noneMatch(c -> c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x202E));
    }

    @Test
    @DisplayName("the cap never splits a surrogate pair")
    void doFilter_blockedLongPathAtSurrogate_notSplit() throws Exception {
        String prefix = "/en/sites/siteA/";
        path("", prefix + "a".repeat(512 - prefix.length() - 1) + "\uD83D\uDE00bbb");
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("a\u2026")
                && m.chars().noneMatch(c -> Character.isSurrogate((char) c)));
    }

    @Test
    @DisplayName("the Referer is logged, sanitised and capped; a dash when absent")
    void doFilter_blocked_logsReferer() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.endsWith("referer: -"));
    }

    @Test
    @DisplayName("an attacker-chosen Referer is sanitised and capped at 512 characters")
    void doFilter_blockedWithHostileReferer_sanitisedAndCapped() throws Exception {
        when(request.getHeader("Referer")).thenReturn("http://x/\r\n\u202E" + "r".repeat(600));
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("referer: http://x/???")
                && m.endsWith("r\u2026") && !m.contains("r".repeat(513)) && !m.contains("\n") && !m.contains("\r"));
    }

    @Test
    @DisplayName("activation logs one INFO line with the order and dispatcher types; construction logs nothing")
    void activate_logsOrderAndDispatcherTypes() {
        verifyNoInteractions(logger);
        filter.activate();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("0.5")
                && m.contains("REQUEST") && m.contains("FORWARD"));
    }

    @Test
    @DisplayName("line separators, including NEL and U+2028/U+2029, cannot forge log lines")
    void doFilter_blockedPathWithLineSeparators_isSanitisedInLog() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/a\r\nb\u0085c d e");
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.chars().noneMatch(
                c -> c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029));
    }

    @Test
    @DisplayName("the logged path is capped at 512 characters plus an ellipsis")
    void doFilter_blockedLongPath_isTruncatedInLog() throws Exception {
        path("/cms/render/live", "/en/sites/siteA/" + "a".repeat(600));
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("a…, referer: -")
                && !m.contains("a".repeat(513))
                && m.contains("a".repeat(480)));
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
    @DisplayName("unmapped hostname passes and the fail-open is logged at DEBUG with the normalised host")
    void doFilter_unmappedHost_passesAndLogsDebug() throws Exception {
        when(request.getServerName()).thenReturn("unknown.example.");
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        assertThat(logged("debug")).anyMatch(m -> m.contains("unknown.example") && !m.contains("unknown.example."));
        assertThat(logged("info")).isEmpty();
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
    @DisplayName("host is passed to the lookup unmodified apart from trailing dots (case is left to Jahia)")
    void doFilter_upperCaseHost_passedToLookupUnmodifiedExceptTrailingDots() throws Exception {
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

    @Test
    @DisplayName("null servletPath and pathInfo pass quietly")
    void doFilter_nullPaths_passes() throws Exception {
        path(null, null);
        run();
        assertPassed();
        verifyNoInteractions(logger);
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
        assertThat(withThrowable("warn")).hasSize(1);
        assertThat(withThrowable("debug")).hasSize(2);
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
        assertThat(withThrowable("warn")).hasSize(1);
        clockMillis.addAndGet(1);
        run();
        assertThat(withThrowable("warn")).hasSize(2);
        run();
        assertThat(withThrowable("warn")).hasSize(2);
        assertThat(withThrowable("debug")).hasSize(2);
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
        assertThat(logged("info")).filteredOn(m -> m.contains("recovered")).hasSize(1);

        sites.set(throwing());
        run();
        assertThat(withThrowable("warn")).hasSize(2);
    }

    @Test
    @DisplayName("null site list passes and counts as a failure")
    void doFilter_siteListNull_passes() throws Exception {
        sites.set(() -> null);
        path("/cms/render/live", "/en/sites/siteA/home.html");
        run();
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
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
        assertThat(logged("warn")).hasSize(1);
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
        }, siteKey -> null, logger, clockMillis::get);
        path("/cms/render/live", "/en/sites/siteA/home.html");

        f.doFilter(request, response, chain);
        f.doFilter(request, response, chain);
        verify(chain, times(2)).doFilter(request, response);
        assertThat(withThrowable("warn")).hasSize(1);
        assertThat(f.isHostLookupFailing()).isTrue();

        broken.set(false);
        f.doFilter(request, response, chain);
        assertThat(logged("info")).filteredOn(m -> m.contains("recovered")).hasSize(1);
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
        assertThat(logged("warn")).isEmpty();
        assertThat(logged("info")).isEmpty();
    }
}
