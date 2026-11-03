import {
    ensureForeignSite,
    expectBlocked,
    expectFileBlocked,
    expectFileServed,
    expectSelectionBlocked,
    expectServedUnguardedThenBlocked,
    expectStatus,
    expectWithheldWhereServed,
    FOREIGN_SERVER_NAME,
    FOREIGN_SITE_KEY,
    FOREIGN_SITE_PATH,
    foreignFileUrlWithKey,
    foreignRenderUrl,
    nodePropertySearchUrl,
    readSiteServerName,
    readSiteUuid,
    searchUrl,
    setSiteServerName,
    sharedFileUrl,
    SITE_KEY,
    SITE_SERVER_NAME,
    waitForMappingToApply
} from '../support/crossSites';

/**
 * The URL forms that used to walk straight past the filter, kept as regression tests.
 *
 *   - content disclosure: the filter matched the raw `getRequestURI()` while `Render` resolves
 *     content from the decoded path, so `/%73ites/` handed one site's content to another's host.
 *   - content disclosure by parameter: a path naming the visitor's own site passed the path check
 *     while a request parameter selected another site: the search module's `src_sites.values`
 *     (search results, excerpts and file text of the other site) and the renderer's `jsite`
 *     (the other site's navigation rendered in the visitor's page).
 *   - metadata disclosure: the /sites node itself (`/sites.html.ajax`) addresses no site key, so it
 *     passed, and lists every site's title, server name and top-level nodes.
 *   - availability: `systemsite` holds Jahia's cross-site shared content; treating it as "a
 *     different site" turned shared assets into errors on every site.
 *
 * Forms that are unobservable over HTTP since a block became a plain 404 (unknown site key, site
 * key as the final path segment, `<key>.html`) are covered by the filter's JUnit tests: the
 * JUnit suite asserts the resolver and decision matrix directly, which HTTP cannot distinguish
 * from "not found".
 */
describe('CrossSitesFilter - former bypasses and false positives', () => {
    // 'c' is %63, so %2563 decodes to %63 and then to 'c' after a second decoding.
    const doubleEncodedKey = `%25${FOREIGN_SITE_KEY.charCodeAt(0).toString(16)}${FOREIGN_SITE_KEY.slice(1)}`;

    // Jackrabbit's expanded-name form "{}name" (%7B%7D) names the same node as "name".
    const expanded = `%7B%7D${FOREIGN_SITE_KEY}`;

    let originalServerName: string | undefined;

    before(() => {
        readSiteServerName().then(value => {
            originalServerName = value;
        });
        ensureForeignSite();
        setSiteServerName(SITE_SERVER_NAME);
        waitForMappingToApply();
    });

    after(() => {
        cy.login();
        if (originalServerName) {
            setSiteServerName(originalServerName);
        } else {
            cy.log('Original j:serverName was never read; nothing was changed, nothing to restore');
        }
    });

    it('blocks a foreign site reached through a percent-encoded /sites/ segment', () => {
        // "%73" is "s". getRequestURI() keeps the escape, so matching on it missed "/sites/";
        // the decoded path the renderer uses reads as an ordinary site path.
        expectBlocked(`/cms/render/live/en/%73ites/${FOREIGN_SITE_KEY}/home.html`, SITE_SERVER_NAME);
    });

    it('does not serve a foreign site addressed with a double-encoded key in the SEO short form', () => {
        // The property is asserted, not a code: on Jahia <= 8.2.3.2 the module blocks this with
        // 404, while newer Jahia core rejects the double encoding with 400 before the module runs.
        // Positive control: where the platform serves the URL on an unguarded host, the mapped host
        // must withhold it; where the platform refuses it, that is logged.
        expectWithheldWhereServed(`/en/sites/${doubleEncodedKey}/home.html`, SITE_SERVER_NAME);
    });

    it('blocks a foreign site named with the expanded-name form in the site key', () => {
        expectBlocked(`/cms/render/live/en/sites/${expanded}/home.html`, SITE_SERVER_NAME);
    });

    it('blocks a foreign site named with the expanded-name form in the /sites/ segment', () => {
        expectBlocked(`/cms/render/live/en/%7B%7Dsites/${FOREIGN_SITE_KEY}/home.html`, SITE_SERVER_NAME);
    });

    it('blocks a foreign site named with the expanded-name form in the SEO short form', () => {
        expectBlocked(`/en/sites/${expanded}/home.html`, SITE_SERVER_NAME);
    });

    it('blocks a foreign live file named with the expanded-name form in the site key', () => {
        expectFileBlocked(foreignFileUrlWithKey(expanded), SITE_SERVER_NAME);
    });

    it('still serves cross-site shared content stored under systemsite', () => {
        // Treating systemsite as "a different site" made shared media fail on each site using it.
        // A real probe file in /sites/systemsite/files is served, with its content, on the mapped host.
        expectFileServed(sharedFileUrl, SITE_SERVER_NAME);
    });

    // The parameter cases run the other way round: the path names FOREIGN_SITE_KEY, which is built
    // from the template set that has a search-results page, on its own hostname FOREIGN_SERVER_NAME,
    // and the parameter selects SITE_KEY, the site that hostname does not map to.
    const ownSearchUrl = searchUrl(FOREIGN_SITE_KEY, FOREIGN_SITE_KEY);

    it('blocks a search on the own site that selects another site with src_sites', () => {
        expectSelectionBlocked(searchUrl(FOREIGN_SITE_KEY, SITE_KEY), ownSearchUrl, FOREIGN_SERVER_NAME);
    });

    it('blocks a search on the own site that adds another site in a comma-separated src_sites', () => {
        expectSelectionBlocked(
            searchUrl(FOREIGN_SITE_KEY, `${FOREIGN_SITE_KEY},${SITE_KEY}`),
            ownSearchUrl,
            FOREIGN_SERVER_NAME
        );
    });

    it('still serves a search scoped to the own site on its own hostname', () => {
        expectStatus(ownSearchUrl, FOREIGN_SERVER_NAME, 200);
    });

    it('blocks a node-property search on the own site that sets no site scope', () => {
        // No term and no src_sites: Jahia still runs it on the node-property criterion, over the whole
        // repository. The control is the same search scoped to the own site.
        expectSelectionBlocked(
            nodePropertySearchUrl(FOREIGN_SITE_KEY),
            nodePropertySearchUrl(FOREIGN_SITE_KEY, FOREIGN_SITE_KEY),
            FOREIGN_SERVER_NAME
        );
    });

    it('still serves a node-property search scoped to the own site on its own hostname', () => {
        expectStatus(nodePropertySearchUrl(FOREIGN_SITE_KEY, FOREIGN_SITE_KEY), FOREIGN_SERVER_NAME, 200);
    });

    it("blocks the own site's page rendered with another site's jsite", () => {
        readSiteUuid(SITE_KEY).then(uuid => {
            expectSelectionBlocked(`${foreignRenderUrl}?jsite=${uuid}`, foreignRenderUrl, FOREIGN_SERVER_NAME);
        });
    });

    it("still serves the own site's page with its own jsite", () => {
        readSiteUuid(FOREIGN_SITE_KEY).then(uuid => {
            expectStatus(`${foreignRenderUrl}?jsite=${uuid}`, FOREIGN_SERVER_NAME, 200);
        });
    });

    it('blocks the /sites root listing, which names every site, on a mapped hostname', () => {
        // The path names no site key, yet the /sites node lists the sites. What it renders differs
        // by Jahia version (8.2.3.2 prints each "Server name"; newer cores may show only keys or
        // links), so the markers are every form that reveals the foreign site, and the ones the
        // unguarded host really carries are the ones that must be withheld.
        ['/cms/render/live/en/sites.html.ajax', '/cms/render/live/en/sites/.html.ajax'].forEach(path => {
            expectServedUnguardedThenBlocked(path, SITE_SERVER_NAME, [
                FOREIGN_SERVER_NAME,
                `${FOREIGN_SITE_PATH}/`,
                FOREIGN_SITE_KEY
            ]);
        });
    });

    it('still serves the own page carrying src_ parameters that start no search', () => {
        // The src_ref tag is not a SearchCriteria property (BeanUtils ignores it, so the filter does), and
        // a blank-only value is ignored: neither a tracking tag nor a blank reference list may break
        // the page. Only a real, non-blank search property makes a request a potential search.
        expectStatus(`${foreignRenderUrl}?src_ref=newsletter`, FOREIGN_SERVER_NAME, 200);
        expectStatus(
            `${foreignRenderUrl}?src_sites.values=${FOREIGN_SITE_KEY}&src_sitesForReferences.values=`,
            FOREIGN_SERVER_NAME,
            200
        );
    });

    it("leaves the encoded form working on the foreign site's own hostname", () => {
        // A filter that blocked everywhere would satisfy every assertion above.
        expectStatus(`/cms/render/live/en/%73ites/${FOREIGN_SITE_KEY}/home.html`, FOREIGN_SERVER_NAME, 200);
        expectStatus(foreignRenderUrl, FOREIGN_SERVER_NAME, 200);
    });
});
