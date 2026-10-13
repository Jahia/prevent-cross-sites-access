import {editSite} from '@jahia/cypress';

/**
 * Shared fixtures for the CrossSitesFilter suites.
 *
 * The filter under test compares two things: the site key embedded in the request path, and
 * the site key Jahia resolves from the Host header. Every spec therefore needs to control
 * both independently, which is what {@link rawGet} provides.
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
 * A site key that does not exist. Requests for it can only ever be answered by the filter
 * (400) or by Jahia's renderer (404), which is what makes "who produced this status" provable
 * instead of inferred.
 */
export const FOREIGN_SITE_KEY = 'another-site';

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
): Cypress.Chainable<RawResponse> => cy.task<RawResponse>('rawRequest', {
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
 * Blocks until the server-name -> site mapping is live.
 *
 * `JahiaSitesService` caches the lookup, so the mutation above is not observable on the very
 * next request. Rather than sleeping, this polls the filter's own behaviour: once a foreign
 * site key is rejected with 400, the mapping has taken effect.
 */
export const waitForMappingToApply = (): void => {
    cy.waitUntil(
        () => rawGet(`/cms/render/live/en/sites/${FOREIGN_SITE_KEY}/home.html`, SITE_SERVER_NAME)
            .then(response => response.status === 400),
        {
            timeout: 30000,
            interval: 1000,
            errorMsg: `Site ${SITE_KEY} never became reachable as ${SITE_SERVER_NAME}; ` +
                'CrossSitesFilter is not enforcing, so the suite would pass vacuously'
        }
    );
};

/** Logs in and returns the session cookie, for requests that must be authenticated. */
export const sessionCookieHeader = (): Cypress.Chainable<Record<string, string>> => {
    cy.login();
    cy.visit('/jahia/dashboard');
    return cy.getCookie('JSESSIONID').then(cookie => {
        if (!cookie) {
            throw new Error('No JSESSIONID after login; the authenticated assertions below would silently run as guest');
        }

        return {Cookie: `JSESSIONID=${cookie.value}`};
    });
};
