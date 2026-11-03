import {DocumentNode} from 'graphql';
import {
    ensureForeignSite,
    FOREIGN_SERVER_NAME,
    FOREIGN_SITE_KEY,
    foreignFileUrl,
    foreignRenderUrl,
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
 * Two real sites exist: `digitall`, reachable on SITE_SERVER_NAME, and a second site reachable
 * on its own hostname. Asking for the second site's content over `digitall`'s hostname is the
 * disclosure the module exists to prevent, and the first test establishes that the content is
 * genuinely served when nothing blocks it - so every 400 below is a refusal of something that
 * would otherwise have been handed over, not a 404 wearing a different number.
 */
describe('CrossSitesFilter - enforcement', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSiteServerName: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSiteServerName.graphql');

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
        ensureForeignSite();
        setSiteServerName(SITE_SERVER_NAME);
        waitForMappingToApply();
    });

    after(() => {
        cy.login();
        setSiteServerName(originalServerName);
    });

    it('serves the second site\'s content on its own hostname', () => {
        // The calibration for everything below: this content is reachable, so a 400 on another
        // hostname is the filter withholding it rather than Jahia failing to find it.
        rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).its('status').should('eq', 200);
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
        // Documented fail-open, asserted at full strength: over an unmapped vhost the second
        // site's content comes back in full. ServerNameToSiteMapper returns "" for an unknown
        // server name, so the filter stands down - reaching Jahia on a bare IP or an internal
        // load-balancer name disables the control completely.
        rawGet(foreignRenderUrl, UNMAPPED_SERVER_NAME).its('status').should('eq', 200);
    });

    it('does not block anything when the hostname is localhost', () => {
        // Url.isLocalhost() short-circuits the lookup to "" before any site is consulted.
        rawGet(foreignRenderUrl, 'localhost').its('status').should('eq', 200);
    });

    it('does not block a site key that names no site', () => {
        // An unknown key is not a cross-site access: Jahia resolves it to no site, falls back to
        // the hostname's site and answers 404 by itself. Blocking it would mean 400 for every
        // unrelated path that merely contains /sites/, such as a module's static resources.
        rawGet('/cms/render/live/en/sites/no-such-site/home.html', SITE_SERVER_NAME)
            .its('status')
            .should('not.eq', 400);
    });

    it('cannot be bypassed with dot segments, because Jahia core rejects them first', () => {
        // On paper this is a bypass: the raw request-target still contains "/sites/digitall/",
        // while the path the renderer resolves has been normalised to the foreign site.
        //
        // In practice the gap is closed upstream of the module: Jahia core's
        // org.jahia.bin.filters.MalformedRequestFilter answers 400 for a non-normalised URI
        // ("request URI is not normalized") and for the encoded form ("path-traversal"),
        // before CrossSitesFilter is ever consulted.
        //
        // The second assertion in each pair is what keeps this honest. Over an unmapped
        // hostname CrossSitesFilter provably cannot act - getSiteKeyByServerName() returns ""
        // and the filter stands down - yet the 400 is still there, so it was never ours. If a
        // future Jahia stopped screening traversal, both assertions would turn 200 and this
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
