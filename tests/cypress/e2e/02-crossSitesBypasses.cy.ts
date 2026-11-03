import {
    ensureForeignSite,
    expectBlocked,
    expectFileBlocked,
    expectFileServed,
    expectStatus,
    expectWithheldWhereServed,
    FOREIGN_SERVER_NAME,
    FOREIGN_SITE_KEY,
    foreignFileUrlWithKey,
    foreignRenderUrl,
    readSiteServerName,
    setSiteServerName,
    sharedFileUrl,
    SITE_SERVER_NAME,
    waitForMappingToApply
} from '../support/crossSites';

/**
 * The URL forms that used to walk straight past the filter, kept as regression tests.
 *
 *   - content disclosure: the filter matched the raw `getRequestURI()` while `Render` resolves
 *     content from the decoded path, so `/%73ites/` handed one site's content to another's host.
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

    it("leaves the encoded form working on the foreign site's own hostname", () => {
        // A filter that blocked everywhere would satisfy every assertion above.
        expectStatus(`/cms/render/live/en/%73ites/${FOREIGN_SITE_KEY}/home.html`, FOREIGN_SERVER_NAME, 200);
        expectStatus(foreignRenderUrl, FOREIGN_SERVER_NAME, 200);
    });
});
