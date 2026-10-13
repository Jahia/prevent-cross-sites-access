import {DocumentNode} from 'graphql';
import {
    FOREIGN_SITE_KEY,
    rawGet,
    setSiteServerName,
    SITE_KEY,
    SITE_PATH,
    SITE_SERVER_NAME,
    waitForMappingToApply
} from '../support/crossSites';

/**
 * ===========================================================================================
 * THIS SPEC IS EXPECTED TO FAIL ON THE CURRENT MODULE (TDD red state).
 * ===========================================================================================
 *
 * Each test states the behaviour the module is supposed to have; each one currently fails,
 * and each failure is a defect found while reading CrossSitesFilter:
 *
 *   - the filter matches on `request.getRequestURI()` - raw, undecoded, un-normalised - while
 *     `Render` resolves content from `request.getPathInfo()`, which the container has already
 *     decoded and normalised. Two parsers, one decision: the first three tests walk through
 *     that gap.
 *   - `StringUtils.substringBetween(uri, "/sites/", "/")` returns null when the site key is
 *     the last path segment, so those URLs are never checked at all.
 *   - `systemsite` holds Jahia's cross-site shared content, so comparing it against the
 *     request's site key turns shared assets into 400s on every site.
 *
 * Delete nothing here to make CI green - fix the filter, or change an assertion deliberately
 * if you disagree with the expectation it encodes (the systemsite one is a judgement call).
 */
describe('CrossSitesFilter - known bypasses and false positives', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSiteServerName: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSiteServerName.graphql');

    let originalServerName = 'localhost';

    before(() => {
        cy.login();
        cy.apollo({query: getSiteServerName, variables: {path: SITE_PATH}})
            .then((result: {data?: {jcr?: {nodeByPath?: {serverName?: {value: string}}}}}) => {
                originalServerName = result?.data?.jcr?.nodeByPath?.serverName?.value || 'localhost';
            });
        setSiteServerName(SITE_SERVER_NAME);
        waitForMappingToApply();
    });

    after(() => {
        cy.login();
        setSiteServerName(originalServerName);
    });

    it('rejects a foreign site reached through dot segments', () => {
        // The filter reads "digitall" out of the raw URI and waves it through; the container
        // normalises the path away before Render resolves it. No encoded slash needed, so
        // Tomcat's ALLOW_ENCODED_SLASH default does not help here.
        const path = `/cms/render/live/en/sites/${SITE_KEY}/../${FOREIGN_SITE_KEY}/home.html`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('rejects a foreign site reached through a percent-encoded /sites/ segment', () => {
        // "%73" is "s". getRequestURI() keeps the escape, so `contains("/sites/")` is false;
        // getPathInfo() decodes it, so the renderer sees a perfectly ordinary site path.
        const path = `/cms/render/live/en/%73ites/${FOREIGN_SITE_KEY}/home.html`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('rejects a foreign site when the site key is the final path segment', () => {
        // substringBetween needs a closing "/" after the opening one; with none it returns
        // null and the request is never evaluated.
        const path = `/cms/render/live/en/sites/${FOREIGN_SITE_KEY}`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('rejects a foreign site when the site key ends the path before a file extension', () => {
        const path = `/cms/render/live/en/sites/${FOREIGN_SITE_KEY}.html`;

        rawGet(path, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('still serves cross-site shared content stored under systemsite', () => {
        // systemsite is where Jahia keeps content shared by every site. Treating its key as
        // "a different site" makes shared media 400 on each site that references it, which is
        // the collateral-damage side of the same comparison.
        const path = '/files/live/sites/systemsite/files';

        rawGet(path, SITE_SERVER_NAME).its('status').should('not.eq', 400);
    });
});
