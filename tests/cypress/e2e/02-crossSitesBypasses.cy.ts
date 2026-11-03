import {DocumentNode} from 'graphql';
import {
    ensureForeignSite,
    FOREIGN_SERVER_NAME,
    FOREIGN_SITE_KEY,
    foreignRenderUrl,
    rawGet,
    setSiteServerName,
    SITE_PATH,
    SITE_SERVER_NAME,
    waitForMappingToApply
} from '../support/crossSites';

/**
 * The URL forms that used to walk straight past the filter, kept as regression tests.
 *
 * Each one was a real defect, found by reading CrossSitesFilter and confirmed against a running
 * Jahia. They differ in severity, and it is worth being exact about which is which:
 *
 *   - **content disclosure.** The filter matched on `request.getRequestURI()`, the raw and
 *     undecoded request-target, while `Render` resolves content from the decoded path.
 *     `/%73ites/` therefore looked like no site path at all here and like an ordinary one to
 *     the renderer. That URL returns 200 with the foreign site's home page, so the old filter
 *     handed a visitor on one site's hostname the full content of another's.
 *   - **the filter bypassed, but nothing exposed.** `StringUtils.substringBetween(uri,
 *     "/sites/", "/")` returns null when the site key is the last path segment, so those URLs
 *     were never checked at all. They address the site node itself, which Jahia does not render
 *     - measured, `/sites/<key>` and `/sites/<key>.html` are 404 even on the site's own
 *     hostname. The gap is real and worth closing, but no content was reachable through it.
 *   - **availability, not disclosure.** `systemsite` holds Jahia's cross-site shared content, so
 *     comparing it against the request's site key turned shared assets into 400s on every site -
 *     the same comparison failing in the opposite direction.
 *
 * The fix was to resolve the site key the way `URLResolver` resolves it, from the decoded path,
 * and to treat only an existing site as a cross-site target.
 */
describe('CrossSitesFilter - former bypasses and false positives', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSiteServerName: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSiteServerName.graphql');

    let originalServerName = 'localhost';

    before(() => {
        cy.login();
        cy.apollo({query: getSiteServerName, variables: {path: SITE_PATH}}).then(
            (result: {data?: {jcr?: {nodeByPath?: {serverName?: {value: string}}}}}) => {
                originalServerName = result?.data?.jcr?.nodeByPath?.serverName?.value || 'localhost';
            }
        );
        ensureForeignSite();
        setSiteServerName(SITE_SERVER_NAME);
        waitForMappingToApply();
    });

    after(() => {
        cy.login();
        setSiteServerName(originalServerName);
    });

    it('rejects a foreign site reached through a percent-encoded /sites/ segment', () => {
        // "%73" is "s". getRequestURI() keeps the escape, so matching on it missed "/sites/"
        // entirely; the decoded path the renderer uses has always read as an ordinary site path.
        const path = `/cms/render/live/en/%73ites/${FOREIGN_SITE_KEY}/home.html`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('rejects a foreign site when the site key is the final path segment', () => {
        // Defence in depth rather than a plugged leak: this URL is a 404 on the foreign site's
        // own hostname too, so nothing was ever served through it. See the header.
        const path = `/cms/render/live/en/sites/${FOREIGN_SITE_KEY}`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('rejects a foreign site when the site key ends the path before a file extension', () => {
        // ".../sites/<key>.<type>" - a site key may itself contain a dot, so where the key ends
        // is decided against the real site list rather than by cutting at the first ".".
        const path = `/cms/render/live/en/sites/${FOREIGN_SITE_KEY}.html`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('still serves cross-site shared content stored under systemsite', () => {
        // Jahia keeps the content shared by every site under systemsite. Treating its key as "a
        // different site" made shared media 400 on each site that referenced it.
        const path = '/files/live/sites/systemsite/files';

        rawGet(path, SITE_SERVER_NAME).its('status').should('not.eq', 400);
    });

    it("leaves every one of those forms working on the site's own hostname", () => {
        // The other half of each fix: none of the above may cost the foreign site access to its
        // own content. A filter that answered 400 everywhere would pass all four tests above.
        // Two of these URLs are 404 on their own hostname - that is Jahia declining to render a
        // site node, not the filter, and 'not 400' is the distinction being asserted.
        rawGet(`/cms/render/live/en/%73ites/${FOREIGN_SITE_KEY}/home.html`, FOREIGN_SERVER_NAME)
            .its('status')
            .should('not.eq', 400);
        rawGet(`/cms/render/live/en/sites/${FOREIGN_SITE_KEY}`, FOREIGN_SERVER_NAME)
            .its('status')
            .should('not.eq', 400);
        rawGet(`/cms/render/live/en/sites/${FOREIGN_SITE_KEY}.html`, FOREIGN_SERVER_NAME)
            .its('status')
            .should('not.eq', 400);
        rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).its('status').should('eq', 200);
    });
});
