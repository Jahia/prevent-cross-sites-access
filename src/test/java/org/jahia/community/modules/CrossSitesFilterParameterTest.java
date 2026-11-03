package org.jahia.community.modules;

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
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
import static org.mockito.Mockito.when;

/**
 * Site-selecting request parameters: the search module's {@code src_sites} family and the renderer's
 * {@code jsite}. A request whose path names the visitor's own site can still select another site
 * through them, so they are checked once the path check has passed.
 */
@ExtendWith(MockitoExtension.class)
class CrossSitesFilterParameterTest {

    private static final String OWN_SEARCH_PATH = "/cms/render/live/en/sites/siteB/home/search-results.html";
    private static final String SITE_A_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SITE_B_ID = "22222222-2222-2222-2222-222222222222";
    private static final String SYSTEMSITE_ID = "33333333-3333-3333-3333-333333333333";

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;
    @Mock
    private Logger logger;

    private Map<String, String> siteIds;
    private List<String> idLookups;
    private RepositoryException idLookupFailure;
    private Map<String, String[]> parameters;
    private CrossSitesFilter filter;

    @BeforeEach
    void setUp() {
        final Map<String, String> hostMap = new HashMap<>();
        hostMap.put("siteb.example", "siteB");
        siteIds = new HashMap<>();
        siteIds.put("siteA", SITE_A_ID);
        siteIds.put("siteB", SITE_B_ID);
        siteIds.put("systemsite", SYSTEMSITE_ID);
        idLookups = new ArrayList<>();
        parameters = new LinkedHashMap<>();
        filter = new CrossSitesFilter(() -> Arrays.asList("siteA", "siteB", "digitall", "systemsite", "acme.fr"),
                hostMap::get, siteKey -> {
                    idLookups.add(siteKey);
                    if (idLookupFailure != null) {
                        throw idLookupFailure;
                    }
                    return siteIds.get(siteKey);
                }, logger, new AtomicLong(1_000)::get);
        lenient().when(request.getServerName()).thenReturn("siteb.example");
        lenient().when(request.getParameterMap()).thenReturn(parameters);
        lenient().when(logger.isInfoEnabled()).thenReturn(true);
        lenient().when(logger.isDebugEnabled()).thenReturn(true);
    }

    /** Adds one occurrence of a parameter; adding a name twice makes it a repeated parameter. */
    private void param(String name, String value) {
        final String[] previous = parameters.get(name);
        final String[] values = previous == null ? new String[1] : Arrays.copyOf(previous, previous.length + 1);
        values[values.length - 1] = value;
        parameters.put(name, values);
    }

    /** The parameters the dx-base-demo-templates search form posts for site siteB. */
    private void ownSearchForm() {
        param("src_originSiteKey", "siteB");
        param("src_terms[0].term", "company");
        param("src_terms[0].match", "all_words");
        param("src_terms[0].fields.siteContent", "true");
        param("src_languages.values", "en");
    }

    private void path(String fullPath) {
        when(request.getServletPath()).thenReturn(fullPath);
        when(request.getPathInfo()).thenReturn(null);
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

    private List<String> logged(String level) {
        return loggerCalls(level).stream()
                .map(args -> MessageFormatter.arrayFormat((String) args[0], Arrays.copyOfRange(args, 1, args.length)).getMessage())
                .collect(Collectors.toList());
    }

    private List<Object[]> loggerCalls(String level) {
        return mockingDetails(logger).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(level))
                .map(Invocation::getArguments)
                .collect(Collectors.toList());
    }

    private static Stream<Arguments> foreignSiteSelections() {
        return Stream.of(
                Arguments.of(Named.of("foreign site", "src_sites.values"), new String[]{"siteA"}),
                Arguments.of(Named.of("repeated: own then foreign", "src_sites.values"), new String[]{"siteB", "siteA"}),
                Arguments.of(Named.of("comma-separated", "src_sites.values"), new String[]{"siteB,siteA"}),
                Arguments.of(Named.of("comma-separated with spaces", "src_sites.values"), new String[]{"siteB , siteA"}),
                Arguments.of(Named.of("single-value property", "src_sites.value"), new String[]{"siteB,siteA"}),
                Arguments.of(Named.of("indexed property", "src_sites.values[0]"), new String[]{"siteA"}),
                Arguments.of(Named.of("braces stripped by BeanUtils' ArrayConverter", "src_sites.values"), new String[]{"{siteA}"}),
                Arguments.of(Named.of("quotes stripped by ArrayConverter", "src_sites.values"), new String[]{"'siteA'"}),
                Arguments.of(Named.of("JCR {} marker", "src_sites.values"), new String[]{"{}siteA"}),
                Arguments.of(Named.of("path below a foreign site", "src_sites.values"), new String[]{"siteA/home"}),
                Arguments.of(Named.of("same-name-sibling index", "src_sites.values"), new String[]{"siteA[1]"}),
                Arguments.of(Named.of("dotted site key", "src_sites.values"), new String[]{"acme.fr"}),
                Arguments.of(Named.of("parent segment", "src_sites.values"), new String[]{"siteB/../siteA"}),
                Arguments.of(Named.of("bare parent segment reaches /", "src_sites.values"), new String[]{".."}),
                Arguments.of(Named.of("every site", "src_sites.values"), new String[]{"-all-"}),
                Arguments.of(Named.of("repeated with a blank value, '/sites/' + '' is every site", "src_sites.values"),
                        new String[]{"siteB", ""}),
                Arguments.of(Named.of("blank value", "src_sites.values"), new String[]{" "}),
                Arguments.of(Named.of("references from a foreign site", "src_sitesForReferences.values"), new String[]{"siteA"}));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("foreignSiteSelections")
    @DisplayName("a search that selects another site, or every site, is answered 404")
    void doFilter_searchSelectsForeignSite_blocked(String name, String[] values) throws Exception {
        ownSearchForm();
        if (!name.startsWith("src_sites.")) {
            param("src_sites.values", "siteB");
        }
        Arrays.stream(values).forEach(v -> param(name, v));
        path(OWN_SEARCH_PATH);
        run();
        assertBlocked();
    }

    private static Stream<Arguments> foreignPathSelections() {
        return Stream.of(
                Arguments.of("src_pagePath.value", "/sites/siteA"),
                Arguments.of("src_pagePath.value", "/sites/siteA/home"),
                Arguments.of("src_pagePath.values", "/sites/siteB/home,/sites/siteA/home"),
                Arguments.of("src_filePath.value", "/sites/siteA/files"),
                Arguments.of("src_filePath.value", "//sites//siteA/files/"),
                Arguments.of("src_filePath.value", "/{}sites/{}siteA/files"),
                Arguments.of("src_filePath.value", "&#47;sites&#47;siteA"),
                Arguments.of("src_pagePath.value", "/sites/siteB/../siteA"),
                // ResultsTag turns a page path outside /sites/ into /sites/, i.e. every site.
                Arguments.of("src_pagePath.value", "home"),
                Arguments.of("src_pagePath.value", "/"),
                Arguments.of("src_pagePath.value", "/sites"),
                Arguments.of("src_filePath.value", "/"));
    }

    @ParameterizedTest(name = "[{index}] {0}={1}")
    @MethodSource("foreignPathSelections")
    @DisplayName("a search path outside the hostname's site is answered 404, whatever src_sites says")
    void doFilter_searchPathOutsideOwnSite_blocked(String name, String value) throws Exception {
        ownSearchForm();
        param("src_sites.values", "siteB");
        param(name, value);
        param("src_pagePath.includeChildren", "true");
        path(OWN_SEARCH_PATH);
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("a search without any site or path scope searches every site and is answered 404")
    void doFilter_unscopedSearch_blocked() throws Exception {
        ownSearchForm();
        path(OWN_SEARCH_PATH);
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("every site"));
    }

    private static Stream<Arguments> allowedSelections() {
        return Stream.of(
                Arguments.of(Named.of("own site and systemsite references, as the search form posts them",
                        "src_sites.values=siteB"), "src_sitesForReferences.values=systemsite"),
                Arguments.of(Named.of("systemsite", "src_sites.values=systemsite"), "src_languages.values=en"),
                Arguments.of(Named.of("own site and systemsite, comma-separated", "src_sites.values=siteB,systemsite"),
                        "src_languages.values=en"),
                Arguments.of(Named.of("unknown site key", "src_sites.values=ghost"), "src_languages.values=en"),
                Arguments.of(Named.of("site key case matters", "src_sites.values=SITEA"), "src_languages.values=en"),
                Arguments.of(Named.of("own page path", "src_sites.values=siteB"), "src_pagePath.value=/sites/siteB/home"),
                Arguments.of(Named.of("page path alone scopes the search", "src_pagePath.value=/sites/siteB/home"),
                        "src_pagePath.includeChildren=true"),
                Arguments.of(Named.of("systemsite file path", "src_filePath.value=/sites/systemsite/files"),
                        "src_filePath.includeChildren=true"),
                Arguments.of(Named.of("unknown site in a path", "src_pagePath.value=/sites/ghost"),
                        "src_languages.values=en"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allowedSelections")
    @DisplayName("a search confined to the hostname's site, systemsite or an unknown key passes")
    void doFilter_searchConfinedToOwnSite_passes(String first, String second) throws Exception {
        ownSearchForm();
        for (String pair : new String[]{first, second}) {
            param(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        path(OWN_SEARCH_PATH);
        run();
        assertPassed();
    }

    /** Adds every "name=value" pair of an unencoded query string; a repeated name repeats the parameter. */
    private void query(String query) {
        for (String pair : query.split("&")) {
            final int eq = pair.indexOf('=');
            param(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    private static Stream<Arguments> requestsThatAreNoPotentialSearch() {
        return Stream.of(
                Arguments.of(Named.of("unknown src_ property (tracking tag)", "src_ref=newsletter")),
                Arguments.of(Named.of("unknown src_ property", "src_x=1")),
                Arguments.of(Named.of("unknown nested src_ property", "src_ref.y=2&src_x[0]=3")),
                Arguments.of(Named.of("property names are case-sensitive in BeanUtils", "src_Sites.values=siteA")),
                Arguments.of(Named.of("a lone blank site value", "src_sites.values=")),
                Arguments.of(Named.of("only blank values", "src_terms[0].term=   &src_sitesForReferences.values=")),
                Arguments.of(Named.of("own site and a blank reference list", "src_sites.values=siteB&src_sitesForReferences.values=")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("requestsThatAreNoPotentialSearch")
    @DisplayName("src_ parameters naming no SearchCriteria property, or carrying only blanks, pass")
    void doFilter_noPotentialSearch_passes(String query) throws Exception {
        query(query);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertPassed();
    }

    private static Stream<Arguments> potentialSearchesBeyondTheHostSite() {
        return Stream.of(
                // Jahia would run no search for these, but the scope is judged whether it runs or not.
                Arguments.of(Named.of("foreign site without a criterion", "src_sites.values=siteA")),
                Arguments.of(Named.of("every site without a criterion", "src_sites.values=-all-")),
                Arguments.of(Named.of("blank term, foreign site", "src_terms[0].term=   &src_sites.values=siteA")),
                Arguments.of(Named.of("'anytime' dates, foreign site", "src_created.type=anytime&src_sites.values=siteA")),
                Arguments.of(Named.of("includeChildren alone has no scope", "src_pagePath.includeChildren=true")),
                Arguments.of(Named.of("a real property that is no criterion, no scope", "src_languages.values=en")),
                // Node-property criteria (SearchCriteria#getProperties), measured live on 8.2.3.2.
                Arguments.of(Named.of("node property value, no scope",
                        "src_languages.values=en&src_properties(jnt:news).desc.value=company&src_properties(jnt:news).desc.match=any_word")),
                Arguments.of(Named.of("node property value, foreign site",
                        "src_properties(jnt:news).desc.value=company&src_sites.values=siteA")),
                Arguments.of(Named.of("node property match alone, no scope", "src_properties(jnt:news).desc.match=any_word")),
                Arguments.of(Named.of("boolean node property, no scope", "src_properties(jnt:page).j:isHomePage.value=true")),
                Arguments.of(Named.of("category node property, every site",
                        "src_properties(jmix:categorized).j:defaultCategory.categoryValue.value=/sites/systemsite/categories&src_sites.values=-all-")),
                Arguments.of(Named.of("date node property, no scope", "src_properties(jnt:news).date.dateValue.type=today")),
                Arguments.of(Named.of("date node property, foreign page path",
                        "src_properties(jnt:news).date.dateValue.type=today&src_pagePath.value=/sites/siteA")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("potentialSearchesBeyondTheHostSite")
    @DisplayName("any potential search whose scope reaches beyond the hostname's site is answered 404")
    void doFilter_potentialSearchBeyondHostSite_blocked(String query) throws Exception {
        query(query);
        path(OWN_SEARCH_PATH);
        run();
        assertBlocked();
    }

    private static Stream<Arguments> nodePropertySearchesOnTheOwnSite() {
        return Stream.of(
                Arguments.of(Named.of("node property value", "src_properties(jnt:news).desc.value=company&src_properties(jnt:news).desc.match=any_word")),
                Arguments.of(Named.of("boolean node property", "src_properties(jnt:page).j:isHomePage.value=true")),
                Arguments.of(Named.of("category node property", "src_properties(jmix:categorized).j:defaultCategory.categoryValue.value=/sites/systemsite/categories")),
                Arguments.of(Named.of("date node property", "src_properties(jnt:news).date.dateValue.type=today")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nodePropertySearchesOnTheOwnSite")
    @DisplayName("a node-property search scoped to the hostname's site and systemsite passes")
    void doFilter_nodePropertySearchOnOwnSite_passes(String query) throws Exception {
        query(query + "&src_languages.values=en&src_sites.values=siteB&src_sitesForReferences.values=systemsite");
        path(OWN_SEARCH_PATH);
        run();
        assertPassed();
    }

    @Test
    @DisplayName("the potential-search properties are read from SearchCriteria itself and match the fallback list")
    void criteriaProperties_introspectedFromSearchCriteria() {
        assertThat(SearchParameterCheck.isIntrospected()).isTrue();
        assertThat(SearchParameterCheck.criteriaProperties())
                .contains("sites", "sitesForReferences", "pagePath", "filePath", "properties", "terms", "nodeType")
                .doesNotContain("ref", "class")
                .isEqualTo(SearchParameterCheck.FALLBACK_PROPERTIES);
    }

    @Test
    @DisplayName("a blank sitesForReferences value is ignored by Jahia and passes in a real search")
    void doFilter_searchWithBlankReferences_passes() throws Exception {
        ownSearchForm();
        query("src_sites.values=siteB&src_sitesForReferences.values=");
        path(OWN_SEARCH_PATH);
        run();
        assertPassed();
    }

    private static Stream<Arguments> searchesJahiaRunsBeyondTheHostSite() {
        return Stream.of(
                Arguments.of(Named.of("node type criterion, foreign site", "src_nodeType=jnt:page&src_sites.values=siteA")),
                Arguments.of(Named.of("file type criterion, no scope", "src_fileType=pdf")),
                Arguments.of(Named.of("author criterion, no scope", "src_createdBy=root")),
                Arguments.of(Named.of("last author criterion, no scope", "src_lastModifiedBy=root")),
                Arguments.of(Named.of("date criterion, no scope", "src_created.type=today")),
                Arguments.of(Named.of("modification date criterion, every site", "src_lastModified.type=range&src_sites.values=-all-")),
                Arguments.of(Named.of("second term, no scope", "src_terms[1].term=x")),
                Arguments.of(Named.of("foreign page path is a criterion on its own", "src_pagePath.value=/sites/siteA")),
                Arguments.of(Named.of("ordinary character splits ArrayConverter tokens", "src_terms[0].term=x&src_sites.values=siteB!siteA")),
                Arguments.of(Named.of("quoted empty element is '/sites/'", "src_terms[0].term=x&src_sites.values=siteB,\"\"")),
                Arguments.of(Named.of("repeated references with a blank element", "src_terms[0].term=x&src_sites.values=siteB"
                        + "&src_sitesForReferences.values=systemsite&src_sitesForReferences.values=")),
                Arguments.of(Named.of("a blank sites parameter may be the one BeanUtils applies last",
                        "src_terms[0].term=x&src_sites.value=siteB&src_sites.values=")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("searchesJahiaRunsBeyondTheHostSite")
    @DisplayName("a search Jahia would run beyond the hostname's site is answered 404")
    void doFilter_searchRunBeyondHostSite_blocked(String query) throws Exception {
        query(query);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("a request without search or jsite parameters does not even look up the hostname")
    void doFilter_noSiteSelectingParameter_passesWithoutParameterCheck() throws Exception {
        param("q", "siteA");
        param("jsiteX", SITE_A_ID);
        path("/cms/render/live/en/users/root.html");
        run();
        assertPassed();
        assertThat(idLookups).isEmpty();
    }

    @Test
    @DisplayName("the SEO short form, whose path names no site at all, is checked as well")
    void doFilter_searchOnShortPath_blocked() throws Exception {
        ownSearchForm();
        param("src_sites.values", "siteA");
        path("/home/search-results.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("a parameter block logs which parameter carried which site, sets siteKey, then sends 404")
    void doFilter_searchBlocked_logsParameterAndSetsSiteKey() throws Exception {
        ownSearchForm();
        param("src_sites.values", "siteA");
        path(OWN_SEARCH_PATH);
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("parameter 'src_sites.values'")
                && m.contains("site 'siteA'") && m.contains("hostname 'siteb.example' maps to site 'siteB'")
                && m.contains("path: " + OWN_SEARCH_PATH) && m.endsWith("referer: -"));
        InOrder order = inOrder(request, response);
        order.verify(request).setAttribute("siteKey", "siteB");
        order.verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("an attacker-chosen parameter name and value are sanitised and capped in the log")
    void doFilter_hostileParameter_sanitisedInLog() throws Exception {
        ownSearchForm();
        param("src_sites.values", "siteB");
        param("src_pagePath.value\r\n‮" + "p".repeat(600), "/sites/siteA");
        path(OWN_SEARCH_PATH);
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> !m.contains("\n") && !m.contains("\r")
                && !m.contains("‮") && !m.contains("p".repeat(513)));
    }

    @Test
    @DisplayName("authoring paths are not checked for site-selecting parameters")
    void doFilter_authoringPathWithForeignParameters_passes() throws Exception {
        param("src_sites.values", "siteA");
        param("jsite", SITE_A_ID);
        path("/cms/render/default/en/sites/siteB/home/search-results.html");
        run();
        assertPassed();
        assertThat(idLookups).isEmpty();
    }

    @Test
    @DisplayName("error pages are not checked for site-selecting parameters")
    void doFilter_errorDispatchWithForeignParameters_passes() throws Exception {
        when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
        param("src_sites.values", "siteA");
        param("jsite", SITE_A_ID);
        path(OWN_SEARCH_PATH);
        run();
        assertPassed();
    }

    @Test
    @DisplayName("an unmapped hostname is not checked for site-selecting parameters")
    void doFilter_unmappedHostWithForeignParameters_passes() throws Exception {
        when(request.getServerName()).thenReturn("unknown.example");
        param("src_sites.values", "siteA");
        param("jsite", SITE_A_ID);
        path(OWN_SEARCH_PATH);
        run();
        assertPassed();
        assertThat(idLookups).isEmpty();
    }

    @Test
    @DisplayName("jsite naming another site's node is answered 404 and logged with the parameter")
    void doFilter_jsiteForeign_blocked() throws Exception {
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertBlocked();
        assertThat(logged("info")).hasSize(1).allMatch(m -> m.contains("parameter 'jsite'") && m.contains(SITE_A_ID)
                && m.contains("maps to site 'siteB'"));
        verify(request).setAttribute("siteKey", "siteB");
    }

    @Test
    @DisplayName("jsite is checked on live paths outside /sites/ too, where Jahia itself appends it")
    void doFilter_jsiteForeignOnNonSitePath_blocked() throws Exception {
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/users/root.html");
        run();
        assertBlocked();
    }

    @Test
    @DisplayName("a repeated jsite is blocked when any of its values is foreign")
    void doFilter_jsiteRepeated_blocked() throws Exception {
        param("jsite", SITE_B_ID);
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertBlocked();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("a blank jsite selects no site (Jahia answers it with an error) and passes without a log line")
    void doFilter_jsiteBlank_passes(String value) throws Exception {
        param("jsite", value);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertPassed();
        assertThat(logged("info")).isEmpty();
        assertThat(idLookups).isEmpty();
    }

    @Test
    @DisplayName("a blank jsite next to a foreign one is still blocked")
    void doFilter_jsiteBlankAndForeign_blocked() throws Exception {
        param("jsite", "");
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertBlocked();
    }

    @ParameterizedTest
    @ValueSource(strings = {SITE_B_ID, SYSTEMSITE_ID})
    @DisplayName("jsite naming the hostname's site or systemsite passes")
    void doFilter_jsiteOwnOrSystemsite_passes(String id) throws Exception {
        param("jsite", id);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("jsite comparison ignores the letter case of the identifier")
    void doFilter_jsiteOwnUpperCase_passes() throws Exception {
        siteIds.put("siteB", "abcdef00-0000-0000-0000-000000000000");
        param("jsite", "ABCDEF00-0000-0000-0000-000000000000");
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertPassed();
    }

    @Test
    @DisplayName("the hostname site's identifier is cached across requests")
    void doFilter_jsiteOwn_lookupCached() throws Exception {
        param("jsite", SITE_B_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        run();
        verify(chain, times(2)).doFilter(request, response);
        assertThat(idLookups).containsExactly("siteB");
    }

    @Test
    @DisplayName("a site re-created under the same key is recognised with its new identifier")
    void doFilter_jsiteAfterSiteRecreated_passes() throws Exception {
        param("jsite", SITE_B_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        final String newId = "44444444-4444-4444-4444-444444444444";
        siteIds.put("siteB", newId);
        parameters.put("jsite", new String[]{newId});
        run();
        verify(chain, times(2)).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
    }

    @Test
    @DisplayName("a failing site-identifier lookup lets jsite through with one WARN, then INFO on recovery")
    void doFilter_jsiteLookupFails_failsOpenWithWarn() throws Exception {
        idLookupFailure = new RepositoryException("repo down");
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        run();
        verify(chain, times(2)).doFilter(request, response);
        verify(response, never()).sendError(any(Integer.class));
        assertThat(loggerCalls("warn")).hasSize(1).allMatch(args -> args[args.length - 1] == idLookupFailure);
        assertThat(filter.isSiteIdLookupFailing()).isTrue();

        idLookupFailure = null;
        run();
        // the recovered request is blocked: sendError, and the chain was not called a third time
        verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
        verify(chain, times(2)).doFilter(any(), any());
        assertThat(logged("info")).filteredOn(m -> m.contains("recovered")).hasSize(1);
        assertThat(filter.isSiteIdLookupFailing()).isFalse();
    }

    @Test
    @DisplayName("a hostname site with no readable node lets jsite through with a WARN")
    void doFilter_jsiteHostSiteMissing_failsOpen() throws Exception {
        idLookupFailure = new PathNotFoundException("/sites/siteB");
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
    }

    @Test
    @DisplayName("a site-identifier lookup returning nothing counts as a failure and fails open")
    void doFilter_jsiteLookupReturnsNull_failsOpen() throws Exception {
        siteIds.clear();
        param("jsite", SITE_A_ID);
        path("/cms/render/live/en/sites/siteB/home.html");
        run();
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
        assertThat(filter.isSiteIdLookupFailing()).isTrue();
    }

    @Test
    @DisplayName("a failing site list lets the search through unchecked, like the path check")
    void doFilter_searchWithFailingSiteList_failsOpen() throws Exception {
        final CrossSitesFilter failing = new CrossSitesFilter(() -> {
            throw new IllegalStateException("repo down");
        }, host -> "siteB", siteKey -> SITE_B_ID, logger, new AtomicLong(1_000)::get);
        ownSearchForm();
        param("src_sites.values", "siteA");
        path("/home/search-results.html");
        failing.doFilter(request, response, chain);
        assertPassed();
        assertThat(logged("warn")).hasSize(1);
        verify(request, never()).setAttribute(eq("siteKey"), any());
    }
}
