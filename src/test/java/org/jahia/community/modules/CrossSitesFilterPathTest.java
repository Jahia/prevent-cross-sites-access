package org.jahia.community.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSitesFilterPathTest {

    private static final Collection<String> SITES = Arrays.asList("siteA", "siteB", "acme", "acme.fr", "systemsite");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/sites/siteA/home.html,siteA",
            "/sites/siteA/,siteA",
            "/sites/siteA,siteA",
            "/sites/siteA.html,siteA",
            "/sites/siteA.default.html,siteA",
            "/sites/siteA.tpl.type,siteA",
            "/cms/render/live/en/sites/siteB/home.html,siteB",
            "/en/sites/siteB/home.html,siteB",
            "/sites/acme.fr.html,acme.fr",
            "/sites/acme.fr.default.html,acme.fr",
            "/sites/acme.fr/home.html,acme.fr",
            "/sites/acme.html,acme",
            "/sites/acme.default.html,acme"
    })
    @DisplayName("resolveSiteKeyFromPath extracts the existing site the path addresses")
    void resolveSiteKeyFromPath_knownSite_returnsKey(String path, String expected) {
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath(path, SITES)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/sites/ghost/home.html",
            "/sites/ghost",
            "/sites/ghost.html",
            "/sites/ghost.default.html",
            "/sites//",
            "/sites//siteA/home.html",
            "/sites/",
            "/sites",
            "/cms/render/live/en/home.html",
            "",
            "/sites/%64igitall/home.html",
            "/sites/siteA.html/extra"
    })
    @DisplayName("resolveSiteKeyFromPath returns null when no existing site is addressed")
    void resolveSiteKeyFromPath_noExistingSite_returnsNull(String path) {
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath(path, SITES)).isNull();
    }

    @Test
    @DisplayName("resolveSiteKeyFromPath only looks at the first /sites/ occurrence")
    void resolveSiteKeyFromPath_twoSitesSegments_firstWins() {
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath("/sites/ghost/x/sites/siteB/home.html", SITES)).isNull();
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath("/sites/siteA/x/sites/siteB/home.html", SITES)).isEqualTo("siteA");
    }

    @Test
    @DisplayName("resolveSiteKeyFromPath resolves nothing for null path, null or empty site list")
    void resolveSiteKeyFromPath_nullOrEmptyInputs_returnsNull() {
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath(null, SITES)).isNull();
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath("/sites/siteA/x", null)).isNull();
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath("/sites/siteA/x", Collections.emptyList())).isNull();
    }

    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(value = {
            "siteb.example,siteb.example",
            "siteb.example.,siteb.example",
            "siteb.example...,siteb.example",
            "SiteB.Example,SiteB.Example",
            "'',''",
            "...,''"
    })
    @DisplayName("normalizeServerName strips every trailing dot and nothing else")
    void normalizeServerName_stripsTrailingDots(String raw, String expected) {
        assertThat(CrossSitesFilter.normalizeServerName(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("normalizeServerName maps null to the empty string")
    void normalizeServerName_null_returnsEmpty() {
        assertThat(CrossSitesFilter.normalizeServerName(null)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/cms/edit/default/en/sites/siteB/home.html",
            "/cms/editframe/default/en/sites/siteB/home.html",
            "/cms/render/default/en/sites/siteB/home.html",
            "/cms/contribute/default/en/sites/siteB/home.html",
            "/files/default/sites/siteB/files/a.png",
            "/repository/default/sites/siteB",
            "/files/default"
    })
    @DisplayName("isAuthoringPath is true for default-workspace paths")
    void isAuthoringPath_defaultWorkspace_true(String path) {
        assertThat(CrossSitesFilter.isAuthoringPath(path)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/cms/render/live/en/sites/siteB/home.html",
            "/files/live/sites/siteB/files/a.png",
            "/en/sites/siteB/home.html",
            "/cms/render/defaults/en/sites/siteB/home.html",
            "/files/defaultx/sites/siteB",
            "/foo/cms/edit/default/sites/siteB",
            "/sites/default/siteB",
            ""
    })
    @DisplayName("isAuthoringPath is false for live and unrelated paths")
    void isAuthoringPath_otherPaths_false(String path) {
        assertThat(CrossSitesFilter.isAuthoringPath(path)).isFalse();
    }

    @Test
    @DisplayName("isAuthoringPath is false for a null path")
    void isAuthoringPath_null_false() {
        assertThat(CrossSitesFilter.isAuthoringPath(null)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
            "/en/sites/{}digitall/home.html,/en/sites/digitall/home.html",
            "/{}sites/digitall/home.html,/sites/digitall/home.html",
            "/files/live/sites/{}site/a.png,/files/live/sites/site/a.png",
            "/a/{}b/{}c,/a/b/c",
            "/sites/{}{}digitall,/sites/{}digitall",
            "/sites/{ }digitall,/sites/{ }digitall",
            "/sites/:digitall,/sites/:digitall",
            "/sites/digitall{},/sites/digitall{}",
            "/sites/digitall,/sites/digitall"
    })
    @DisplayName("normalizeJcrPath strips one leading {} per segment, as JCR does")
    void normalizeJcrPath_stripsOneEmptyNamespacePerSegment(String raw, String expected) {
        assertThat(CrossSitesFilter.normalizeJcrPath(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("normalizeJcrPath maps null to the empty string")
    void normalizeJcrPath_null_returnsEmpty() {
        assertThat(CrossSitesFilter.normalizeJcrPath(null)).isEmpty();
    }

    @Test
    @DisplayName("site key matching is case-sensitive, like JCR")
    void resolveSiteKeyFromPath_differentCase_returnsNull() {
        assertThat(CrossSitesFilter.resolveSiteKeyFromPath("/sites/SITEA/home.html", SITES)).isNull();
    }

    @Test
    @DisplayName("an authoring path hidden behind {} is not authoring")
    void isAuthoringPath_emptyNamespaceMarker_false() {
        assertThat(CrossSitesFilter.isAuthoringPath("/cms/render/{}default/en/sites/siteB/home.html")).isFalse();
    }

    @Test
    @DisplayName("sites path prefix is derived from the JCR constant")
    void sitesPathPart_derivedFromJcrConstant() {
        List<String> parts = Collections.singletonList(CrossSitesFilter.SITES_PATH_PART);
        assertThat(parts).containsExactly("/sites/");
    }
}
