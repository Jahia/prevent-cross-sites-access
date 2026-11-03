import {DocumentNode} from 'graphql';
import {
    FOREIGN_SITE_KEY,
    rawGet,
    sessionCookieHeader,
    setSiteServerName,
    SITE_KEY,
    SITE_PATH,
    SITE_SERVER_NAME,
    UNMAPPED_SERVER_NAME,
    waitForMappingToApply
} from '../support/crossSites';

/**
 * Behaviour the module is meant to have.
 *
 * The suite maps a hostname to `digitall`, then asks for content belonging to a different
 * site key over that hostname. `another-site` does not exist, so the only two answers Jahia
 * can give are 400 (CrossSitesFilter rejected it) or 404 (the renderer did not find it).
 * Asserting on that pair is what makes "the filter did this" provable rather than assumed.
 */
describe('CrossSitesFilter - enforcement', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSiteServerName: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSiteServerName.graphql');

    const foreignRenderUrl = `/cms/render/live/en/sites/${FOREIGN_SITE_KEY}/home.html`;
    const foreignFileUrl = `/files/live/sites/${FOREIGN_SITE_KEY}/files/logo.png`;
    const ownRenderUrl = `/cms/render/live/en/sites/${SITE_KEY}/home.html`;
    const ownEditUrl = `/cms/edit/default/en/sites/${SITE_KEY}/home.html`;
    const noSiteSegmentUrl = '/cms/render/live/en/no-sites-segment.html';

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

    it('rejects another site\'s content requested over the mapped hostname', () => {
        rawGet(foreignRenderUrl, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('rejects another site\'s files requested over the mapped hostname', () => {
        rawGet(foreignFileUrl, SITE_SERVER_NAME).its('status').should('eq', 400);
    });

    it('serves the mapped site\'s own content exactly as it would with no filter in play', () => {
        // Self-calibrating: whatever the unguarded answer is (200 when digitall's home page is
        // published, 404 when it is not), the filter must not change it for the site's own host.
        rawGet(ownRenderUrl, UNMAPPED_SERVER_NAME).then(unguarded => {
            rawGet(ownRenderUrl, SITE_SERVER_NAME)
                .its('status')
                .should('eq', unguarded.status);
        });
    });

    it('ignores request paths that carry no /sites/ segment', () => {
        rawGet(noSiteSegmentUrl, SITE_SERVER_NAME).its('status').should('not.eq', 400);
    });

    it('does not block anything when the hostname maps to no site', () => {
        // Documented fail-open: ServerNameToSiteMapper returns "" for an unknown server name,
        // so the filter stands down entirely. Reaching Jahia on an unmapped vhost - a bare IP,
        // an internal load-balancer name - disables the control.
        rawGet(foreignRenderUrl, UNMAPPED_SERVER_NAME).its('status').should('not.eq', 400);
    });

    it('does not block anything when the hostname is localhost', () => {
        // Url.isLocalhost() short-circuits the lookup to "" before any site is consulted.
        rawGet(foreignRenderUrl, 'localhost').its('status').should('not.eq', 400);
    });

    it('cannot be bypassed with dot segments, because Jahia core rejects them first', () => {
        // On paper this is a bypass: the filter matches on the raw getRequestURI(), which still
        // contains "/sites/digitall/", while Render resolves getPathInfo(), which the container
        // has already normalised to "/sites/another-site/". Two parsers, one decision.
        //
        // In practice the gap is closed upstream of the module: Jahia core's
        // org.jahia.bin.filters.MalformedRequestFilter answers 400 for a non-normalised URI
        // ("request URI is not normalized") and for the encoded form ("path-traversal"),
        // before CrossSitesFilter is ever consulted.
        //
        // The second assertion in each pair is what keeps this honest. Over an unmapped
        // hostname CrossSitesFilter provably cannot act - getSiteKeyByServerName() returns ""
        // and the filter stands down - yet the 400 is still there, so it was never ours. If a
        // future Jahia stopped screening traversal, both assertions would turn 404 and this
        // test would report that the module is now exposed.
        const dotSegmentUrl = `/cms/render/live/en/sites/${SITE_KEY}/../${FOREIGN_SITE_KEY}/home.html`;
        const encodedDotSegmentUrl = `/cms/render/live/en/sites/${SITE_KEY}/%2e%2e/${FOREIGN_SITE_KEY}/home.html`;

        rawGet(dotSegmentUrl, SITE_SERVER_NAME).its('status').should('eq', 400);
        rawGet(dotSegmentUrl, UNMAPPED_SERVER_NAME).its('status').should('eq', 400);

        rawGet(encodedDotSegmentUrl, SITE_SERVER_NAME).its('status').should('eq', 400);
        rawGet(encodedDotSegmentUrl, UNMAPPED_SERVER_NAME).its('status').should('eq', 400);
    });

    describe('authenticated editors', () => {
        it('can still open edit mode for the site on its own hostname', () => {
            // The regression that would hurt most: the filter runs after Shiro but ignores the
            // authenticated user, so a mistake here locks authors out of their own site.
            sessionCookieHeader().then(headers => {
                rawGet(ownEditUrl, SITE_SERVER_NAME, headers)
                    .its('status')
                    .should('not.eq', 400);
            });
        });

        it('are blocked from another site just like anonymous visitors', () => {
            // Confirms the control is authentication-blind by design: holding a session, even
            // root's, does not exempt a request.
            sessionCookieHeader().then(headers => {
                rawGet(foreignRenderUrl, SITE_SERVER_NAME, headers)
                    .its('status')
                    .should('eq', 400);
            });
        });
    });
});
