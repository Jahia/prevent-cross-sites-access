import {
    ensureForeignSite,
    expectBlocked,
    expectFileBlocked,
    expectForeignContentServed,
    expectNotBlocked,
    expectNotServed,
    expectSameAsOwnHostWithoutLeak,
    expectSameStatusAs,
    expectStatus,
    FOREIGN_SERVER_NAME,
    FOREIGN_SITE_KEY,
    foreignDefaultFileUrl,
    foreignFileUrl,
    foreignRenderUrl,
    getStaticAssetUrl,
    readSiteServerName,
    sessionCookieHeader,
    setSiteServerName,
    SITE_KEY,
    SITE_SERVER_NAME,
    UNMAPPED_SERVER_NAME,
    waitForMappingToApply
} from '../support/crossSites';

/**
 * Behaviour the module is meant to have.
 *
 * Two real sites exist: `digitall`, reachable on SITE_SERVER_NAME, and a second site reachable
 * on its own hostname. Asking for the second site's live content over `digitall`'s hostname is the
 * disclosure the module exists to prevent. A blocked request is answered with Jahia's normal 404,
 * so every blocked assertion goes through expectBlocked(), which also proves the same URL is a
 * 200 on an unguarded host and that the body does not carry the foreign page.
 *
 * Authoring (default-workspace) URLs are deliberately not enforced; Jahia login and ACLs govern
 * them. Unknown site keys, key-as-final-segment forms and the like are 404 either way over HTTP
 * and are covered by the JUnit tests of the filter instead.
 */
describe('CrossSitesFilter - enforcement', () => {
    const ownRenderUrl = `/cms/render/live/en/sites/${SITE_KEY}/home.html`;
    // The frame Page Composer loads to edit a page. /cms/edit/... is no use here: on Jahia 8.2 it
    // redirects everyone, guest included, to /jahia/page-composer/..., whatever the session.
    const ownEditUrl = `/cms/editframe/default/en/sites/${SITE_KEY}/home.html`;

    let originalServerName: string | undefined;

    before(() => {
        // Captured before anything is changed; a failure here aborts the spec untouched.
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

    it("serves the second site's content on its own hostname", () => {
        // The calibration for everything below, and proof the derived markers really are in the page.
        expectForeignContentServed(foreignRenderUrl, FOREIGN_SERVER_NAME);
    });

    it("blocks another site's live content requested over the mapped hostname", () => {
        expectBlocked(foreignRenderUrl, SITE_SERVER_NAME);
    });

    it("blocks another site's live files requested over the mapped hostname", () => {
        expectFileBlocked(foreignFileUrl, SITE_SERVER_NAME);
    });

    it('blocks a trailing-dot hostname exactly like the plain mapped hostname', () => {
        expectBlocked(foreignRenderUrl, `${SITE_SERVER_NAME}.`);
    });

    it('blocks an upper-case hostname exactly like the plain mapped hostname', () => {
        expectBlocked(foreignRenderUrl, SITE_SERVER_NAME.toUpperCase());
    });

    it("serves the mapped site's own content exactly as it would with no filter in play", () => {
        // The digitall live home page must really be served (200) on its own host, including via the
        // normalised forms of that host, and the filter must not change the unguarded answer.
        [SITE_SERVER_NAME, `${SITE_SERVER_NAME}.`, SITE_SERVER_NAME.toUpperCase()].forEach(host => {
            expectStatus(ownRenderUrl, host, 200);
            expectSameStatusAs(ownRenderUrl, host);
        });
    });

    it('ignores request paths that carry no /sites/ segment', () => {
        // A URL that EXISTS and has no /sites/ segment: a static module asset the foreign page
        // references. It must be served (200) on the mapped host just as on the unguarded one.
        const asset = getStaticAssetUrl();
        expectStatus(asset, UNMAPPED_SERVER_NAME, 200);
        expectStatus(asset, SITE_SERVER_NAME, 200);
    });

    it('does not block anything when the hostname maps to no site', () => {
        // Documented fail-open: an unmapped vhost (bare IP, internal load-balancer name) disables
        // the control, and the second site's content comes back in full.
        expectForeignContentServed(foreignRenderUrl, UNMAPPED_SERVER_NAME);
    });

    it('does not block anything when the hostname is localhost', () => {
        // Url.isLocalhost() short-circuits the lookup before any site is consulted.
        expectForeignContentServed(foreignRenderUrl, 'localhost');
    });

    // An unknown site key over the mapped host is not asserted here: it is a 404 whether the
    // filter lets it through or not, so no HTTP assertion could fail. Covered by the filter's JUnit tests.

    it('does not enforce authoring URLs (default workspace files) for another site', () => {
        sessionCookieHeader().then(headers => {
            expectNotBlocked(foreignDefaultFileUrl, SITE_SERVER_NAME, FOREIGN_SERVER_NAME, headers);
        });
    });

    it('cannot be bypassed with dot segments (tripwire on the layer before the module)', () => {
        // On paper this is a bypass: the raw request-target still contains "/sites/digitall/",
        // while the path the renderer resolves has been normalised to the foreign site.
        // In practice a layer before the module (Jahia core's MalformedRequestFilter where present)
        // answers 400 for a non-normalised URI and for the encoded form, before CrossSitesFilter
        // runs. This test is a tripwire on that layer, not on the module.
        //
        // Over the mapped host the property asserted is "foreign content not served", true on every
        // Jahia version whichever layer refuses. Over an unmapped host the filter cannot act, so
        // the 400 there is the platform's; if it stopped screening traversal both would serve content.
        const dotSegmentUrl = `/cms/render/live/en/sites/${SITE_KEY}/../${FOREIGN_SITE_KEY}/home.html`;
        const encodedDotSegmentUrl = `/cms/render/live/en/sites/${SITE_KEY}/%2e%2e/${FOREIGN_SITE_KEY}/home.html`;

        expectNotServed(dotSegmentUrl, SITE_SERVER_NAME);
        expectStatus(dotSegmentUrl, UNMAPPED_SERVER_NAME, 400);

        expectNotServed(encodedDotSegmentUrl, SITE_SERVER_NAME);
        expectStatus(encodedDotSegmentUrl, UNMAPPED_SERVER_NAME, 400);
    });

    describe('guests and authoring URLs', () => {
        [
            ['default-workspace render', `/cms/render/default/en/sites/${FOREIGN_SITE_KEY}/home.html`],
            ['edit frame', `/cms/editframe/default/en/sites/${FOREIGN_SITE_KEY}/home.html`],
            ['default-workspace file', foreignDefaultFileUrl]
        ].forEach(([label, url]) => {
            it(`gets on the mapped hostname exactly what it gets on the foreign host for the ${label} URL`, () => {
                // No session: the authoring exemption must not become a way to read foreign content.
                // Jahia login and ACLs answer (404 or a redirect) identically on both hosts, no leak.
                expectSameAsOwnHostWithoutLeak(url, SITE_SERVER_NAME, FOREIGN_SERVER_NAME);
            });
        });
    });

    describe('authenticated editors', () => {
        let authHeaders: Record<string, string> = {};

        before(() => {
            // Proves the session is authenticated (default-workspace page: 200 with the cookie, 404 for a guest).
            sessionCookieHeader().then(headers => {
                authHeaders = headers;
            });
        });

        it('can still open the edit frame for the site on its own hostname', () => {
            // The regression that would hurt most: a mistake here locks authors out of their own site.
            expectStatus(ownEditUrl, SITE_SERVER_NAME, 200, authHeaders);
        });

        it('are blocked from another site live content just like anonymous visitors', () => {
            // The control is authentication-blind by design for live content.
            expectBlocked(foreignRenderUrl, SITE_SERVER_NAME, authHeaders);
        });

        [
            ['edit frame', `/cms/editframe/default/en/sites/${FOREIGN_SITE_KEY}/home.html`],
            ['edit', `/cms/edit/default/en/sites/${FOREIGN_SITE_KEY}/home.html`],
            ['default-workspace render', `/cms/render/default/en/sites/${FOREIGN_SITE_KEY}/home.html`]
        ].forEach(([label, url]) => {
            it(`are not blocked on another site's ${label} URL over the mapped hostname`, () => {
                // Authoring URLs are not enforced: same status as on the foreign site's own hostname.
                expectNotBlocked(url, SITE_SERVER_NAME, FOREIGN_SERVER_NAME, authHeaders);
            });
        });
    });
});
