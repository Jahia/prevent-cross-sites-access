import {createSite, editSite, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * Shared fixtures for the CrossSitesFilter suites.
 *
 * The filter under test compares two things: the site key the request path addresses, and the
 * site key Jahia resolves from the Host header. Every spec therefore needs to control both
 * independently, which is what {@link rawGet} provides.
 */

/** Host/port the Jahia container is reachable on. Only a transport detail - never an assertion input. */
export const JAHIA_HOST: string = (Cypress.env('JAHIA_HOST') as string) || 'jahia';
export const JAHIA_PORT: number = Number(Cypress.env('JAHIA_PORT') || 8080);

/** The site the specs map a hostname to. */
export const SITE_KEY = 'digitall';
export const SITE_PATH = `/sites/${SITE_KEY}`;

/**
 * Hostname mapped to {@link SITE_KEY} for the duration of a spec. Requests carrying this Host
 * are "on the site's own hostname", so the filter must let that site's own content through.
 */
export const SITE_SERVER_NAME = 'prevent-cross-sites.test';

/** Hostname deliberately mapped to no site at all - exercises the fail-open branch. */
export const UNMAPPED_SERVER_NAME = 'not-a-jahia-site.test';

/**
 * A second, real site: the thing the filter is supposed to keep out of {@link SITE_KEY}'s
 * hostname.
 *
 * It has to genuinely exist and genuinely serve content. A made-up site key proves nothing here,
 * because Jahia resolves an unknown key to no site at all, falls back to the hostname's site and
 * answers 404 on its own - so "404 instead of the content" would be true with the filter
 * uninstalled. With a real site the comparison becomes 200 (the content is served) against 400
 * (the filter refused), which is the actual disclosure the module exists to prevent.
 */
export const FOREIGN_SITE_KEY = 'crosssitesother';
export const FOREIGN_SITE_PATH = `/sites/${FOREIGN_SITE_KEY}`;
export const FOREIGN_SERVER_NAME = 'cross-sites-other.test';
export const FOREIGN_TEMPLATE_SET = 'dx-base-demo-templates';

/** Foreign-site URLs the specs ask for, from one place so both specs stay in step. */
export const foreignRenderUrl = `/cms/render/live/en${FOREIGN_SITE_PATH}/home.html`;
export const foreignFileUrl = `/files/live${FOREIGN_SITE_PATH}/files/logo.png`;

const SITE_CREATION_TIMEOUT_MS = 180000;
const MAPPING_TIMEOUT_MS = 30000;

export interface RawResponse {
    status: number;
    headers: Record<string, string | string[]>;
    body: string;
}

/**
 * Sends `path` to Jahia byte for byte, under an arbitrary Host header.
 *
 * @param path request-target, passed through with no normalisation or re-encoding
 * @param host value of the Host header, i.e. what `request.getServerName()` will return
 * @param extraHeaders additional headers, e.g. a session cookie
 */
export const rawGet = (
    path: string,
    host: string,
    extraHeaders: Record<string, string> = {}
): Cypress.Chainable<RawResponse> =>
    cy.task<RawResponse>('rawRequest', {
        host: JAHIA_HOST,
        port: JAHIA_PORT,
        path,
        // Port included so the Host header matches what a browser would send; getServerName()
        // strips it again, which is the only part the filter's site lookup consumes.
        headers: {Host: `${host}:${JAHIA_PORT}`, ...extraHeaders}
    });

/** Points a hostname at {@link SITE_KEY}. */
export const setSiteServerName = (serverName: string): void => {
    editSite(SITE_KEY, {serverName});
};

/**
 * Makes sure the second site exists and is serving its home page in live.
 *
 * Idempotent and deliberately not undone afterwards: the probe below is the same request the
 * assertions make, so a run against an instance that already has the site skips straight past
 * the slowest part of the fixture. The CI container is thrown away either way.
 */
export const ensureForeignSite = (): void => {
    rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).then(response => {
        if (response.status === 200) {
            return;
        }

        cy.log(`Creating the second site '${FOREIGN_SITE_KEY}' (probe returned ${response.status})`);
        createSite(FOREIGN_SITE_KEY, {
            languages: 'en',
            templateSet: FOREIGN_TEMPLATE_SET,
            serverName: FOREIGN_SERVER_NAME,
            locale: 'en'
        });
        publishAndWaitJobEnding(FOREIGN_SITE_PATH, ['en']);

        cy.waitUntil(() => rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).then(live => live.status === 200), {
            timeout: SITE_CREATION_TIMEOUT_MS,
            interval: 2000,
            errorMsg:
                `Site ${FOREIGN_SITE_KEY} never served ${foreignRenderUrl} on its own hostname; ` +
                'without content there the cross-site assertions could not tell a block from an empty site'
        });
    });
};

/**
 * Blocks until the server-name -> site mapping is live.
 *
 * `JahiaSitesService` caches the lookup, so the mutation above is not observable on the very
 * next request. Rather than sleeping, this polls the filter's own behaviour: once the foreign
 * site is rejected on {@link SITE_SERVER_NAME}, the mapping has taken effect.
 */
export const waitForMappingToApply = (): void => {
    cy.waitUntil(() => rawGet(foreignRenderUrl, SITE_SERVER_NAME).then(response => response.status === 400), {
        timeout: MAPPING_TIMEOUT_MS,
        interval: 1000,
        errorMsg:
            `Site ${SITE_KEY} never became reachable as ${SITE_SERVER_NAME}; ` +
            'CrossSitesFilter is not enforcing, so the suite would pass vacuously'
    });
};

/** Logs in and returns the session cookie, for requests that must be authenticated. */
export const sessionCookieHeader = (): Cypress.Chainable<Record<string, string>> => {
    cy.login();
    cy.visit('/jahia/dashboard');
    return cy.getCookie('JSESSIONID').then(cookie => {
        if (!cookie) {
            throw new Error(
                'No JSESSIONID after login; the authenticated assertions below would silently run as guest'
            );
        }

        return {Cookie: `JSESSIONID=${cookie.value}`};
    });
};
