import {DocumentNode} from 'graphql';
import gql from 'graphql-tag';
import {createSite, editSite, publishAndWaitJobEnding, uploadFile} from '@jahia/cypress';

/**
 * Shared fixtures and assertion helpers for the CrossSitesFilter suites.
 *
 * The filter under test compares two things: the site key the request path addresses, and the
 * site key Jahia resolves from the Host header. Every spec therefore needs to control both
 * independently, which is what {@link rawGet} provides.
 *
 * A request the filter blocks is answered with Jahia's normal 404 page, so a status alone cannot
 * tell "blocked" from "not found". Every blocked assertion therefore goes through
 * {@link expectBlocked}, which also proves that content existed to be withheld.
 */

/** Host/port the Jahia container is reachable on. Only a transport detail - never an assertion input. */
export const JAHIA_HOST: string = (Cypress.env('JAHIA_HOST') as string) || 'jahia';
export const JAHIA_PORT = Number(Cypress.env('JAHIA_PORT') || 8080);

/** The site the specs map a hostname to. */
export const SITE_KEY = 'digitall';
export const SITE_PATH = `/sites/${SITE_KEY}`;

/**
 * Hostname mapped to {@link SITE_KEY} for the duration of a spec. Requests carrying this Host
 * are "on the site's own hostname", so the filter must let that site's own content through.
 */
export const SITE_SERVER_NAME = 'prevent-cross-sites.test';

/** Hostname deliberately mapped to no site at all - the "unguarded" baseline, the filter cannot act on it. */
export const UNMAPPED_SERVER_NAME = 'not-a-jahia-site.test';

/**
 * A second, real site: the thing the filter is supposed to keep out of {@link SITE_KEY}'s
 * hostname. It has to genuinely exist and genuinely serve content, otherwise "refused" and
 * "empty" look the same.
 */
export const FOREIGN_SITE_KEY = 'crosssitesother';
export const FOREIGN_SITE_PATH = `/sites/${FOREIGN_SITE_KEY}`;
export const FOREIGN_SERVER_NAME = 'cross-sites-other.test';
export const FOREIGN_TEMPLATE_SET = 'dx-base-demo-templates';

/** Foreign-site URLs the specs ask for, from one place so both specs stay in step. */
export const foreignRenderUrl = `/cms/render/live/en${FOREIGN_SITE_PATH}/home.html`;

/**
 * A file the suite uploads to the foreign site (fixture `cypress/fixtures/crossSitesProbe.txt`),
 * so that file URLs have real content to withhold. A fresh site has no files of its own.
 */
const PROBE_FIXTURE = 'crossSitesProbe.txt';
const PROBE_NAME = 'crossSitesProbe.txt';
const FOREIGN_FILES_PATH = `${FOREIGN_SITE_PATH}/files`;
const FOREIGN_PROBE_PATH = `${FOREIGN_FILES_PATH}/${PROBE_NAME}`;
export const foreignFileUrl = `/files/live${FOREIGN_PROBE_PATH}`;
export const foreignDefaultFileUrl = `/files/default${FOREIGN_PROBE_PATH}`;

/** An edit-mode page that only an authenticated user is served (guests are redirected or refused). */
const AUTH_PROBE_URL = `/cms/edit/default/en${SITE_PATH}/home.html`;

const HTTP_OK = 200;
const HTTP_NOT_FOUND = 404;
const SITE_CREATION_TIMEOUT_MS = 180000;
const MAPPING_TIMEOUT_MS = 30000;

export interface RawResponse {
    status: number;
    headers: Record<string, string | string[]>;
    body: string;
}

type Headers = Record<string, string>;

interface JcrResult<T> {
    errors?: {message: string}[];
    data?: T;
}

// eslint-disable-next-line @typescript-eslint/no-var-requires
const getSiteNames: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSiteNames.graphql');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const getSiteServerName: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSiteServerName.graphql');

const findNodes = gql`
    query findNodes($workspace: Workspace!, $query: String!) {
        jcr(workspace: $workspace) {
            nodesByQuery(query: $query) {
                nodes {
                    name
                }
            }
        }
    }
`;

/** Foreign-page fragments derived at runtime by {@link ensureForeignSite}; never hard-coded. */
let foreignMarkers: string[] = [];

/** Content of the uploaded probe file, read from the fixture by {@link ensureForeignSite}. */
let foreignFileMarkers: string[] = [];

/** Human-readable description of a request, used in every failure message. */
export const describeRequest = (path: string, host: string, extraHeaders: Headers = {}): string =>
    `GET ${path} [Host: ${host}${Object.keys(extraHeaders).length > 0 ? ', with extra headers' : ''}]`;

/**
 * Sends `path` to Jahia byte for byte, under an arbitrary Host header.
 *
 * @param path request-target, passed through with no normalisation or re-encoding
 * @param host value of the Host header, i.e. what `request.getServerName()` will return
 * @param extraHeaders additional headers, e.g. a session cookie
 */
export const rawGet = (path: string, host: string, extraHeaders: Headers = {}): Cypress.Chainable<RawResponse> =>
    cy.task<RawResponse>('rawRequest', {
        host: JAHIA_HOST,
        port: JAHIA_PORT,
        path,
        // Port included so the Host header matches what a browser would send; getServerName()
        // strips it again, which is the only part the filter's site lookup consumes.
        headers: {Host: `${host}:${JAHIA_PORT}`, ...extraHeaders}
    });

const getMarkers = (): string[] => {
    if (foreignMarkers.length === 0) {
        throw new Error('No foreign-site marker available; call ensureForeignSite() in before() first');
    }

    return foreignMarkers;
};

const expectNoForeignContent = (response: RawResponse, request: string, markers: string[] = getMarkers()): void => {
    markers.forEach(marker => {
        expect(
            response.body.includes(marker),
            `${request} -> status ${response.status}: body leaks foreign content (found ${JSON.stringify(marker)})`
        ).to.eq(false);
    });
};

/** Asserts the exact status of a request; yields the response. */
export const expectStatus = (
    path: string,
    host: string,
    expected: number,
    extraHeaders: Headers = {}
): Cypress.Chainable<RawResponse> =>
    rawGet(path, host, extraHeaders).then(response => {
        expect(response.status, `${describeRequest(path, host, extraHeaders)} -> expected ${expected}`).to.eq(expected);
        return response;
    });

/** Asserts the foreign page is genuinely served (200 and carrying the derived markers) on `host`. */
export const expectForeignContentServed = (path: string, host: string, extraHeaders: Headers = {}): void => {
    expectStatus(path, host, HTTP_OK, extraHeaders).then(response => {
        getMarkers().forEach(marker => {
            expect(
                response.body.includes(marker),
                `${describeRequest(path, host, extraHeaders)} -> 200 but body lacks ${JSON.stringify(marker)}`
            ).to.eq(true);
        });
    });
};

/** Asserts `host` gets the same status as `baselineHost` for `path` (the comparison is to an unguarded answer). */
export const expectSameStatusAs = (
    path: string,
    host: string,
    baselineHost: string = UNMAPPED_SERVER_NAME,
    extraHeaders: Headers = {}
): Cypress.Chainable<RawResponse> =>
    rawGet(path, baselineHost, extraHeaders).then(baseline =>
        rawGet(path, host, extraHeaders).then(response => {
            expect(
                response.status,
                `${describeRequest(path, host, extraHeaders)} -> ${response.status}, but ` +
                    `${describeRequest(path, baselineHost, extraHeaders)} -> ${baseline.status}`
            ).to.eq(baseline.status);
            return response;
        })
    );

/**
 * Asserts a request for foreign content is not enforced against: same status as on the baseline
 * host, and that status is not 404 (a 404 would be indistinguishable from a block).
 */
export const expectNotBlocked = (
    path: string,
    host: string,
    baselineHost: string,
    extraHeaders: Headers = {}
): void => {
    expectSameStatusAs(path, host, baselineHost, extraHeaders).then(response => {
        expect(
            response.status,
            `${describeRequest(path, host, extraHeaders)} -> 404 equals a block; the comparison proves nothing`
        ).to.not.eq(HTTP_NOT_FOUND);
    });
};

/**
 * THE blocked assertion. Proves, in one place, that:
 *   1. the same URL on an unguarded host returns 200 - there was content to withhold;
 *   2. over `host` the answer is 404 (Jahia's normal error page);
 *   3. the body neither contains the foreign site's markers nor equals the unguarded body.
 */
export const expectBlocked = (
    path: string,
    host: string,
    extraHeaders: Headers = {},
    markers: string[] = getMarkers()
): void => {
    const request = describeRequest(path, host, extraHeaders);
    const unguarded = describeRequest(path, UNMAPPED_SERVER_NAME, extraHeaders);

    expectStatus(path, UNMAPPED_SERVER_NAME, HTTP_OK, extraHeaders).then(baseline => {
        rawGet(path, host, extraHeaders).then(response => {
            expect(response.status, `${request} -> expected 404 (blocked); ${unguarded} serves 200`).to.eq(
                HTTP_NOT_FOUND
            );
            expectNoForeignContent(response, request, markers);
            expect(response.body, `${request} -> body identical to the unguarded body`).to.not.eq(baseline.body);
        });
    });
};

/** {@link expectBlocked} for a file URL: the marker is the uploaded file's own content, not a page title. */
export const expectFileBlocked = (path: string, host: string, extraHeaders: Headers = {}): void => {
    if (foreignFileMarkers.length === 0) {
        throw new Error('No foreign file marker available; call ensureForeignSite() in before() first');
    }

    expectBlocked(path, host, extraHeaders, foreignFileMarkers);
};

/**
 * Asserts foreign content is NOT served, whatever the refusing layer: status other than 200 and no
 * marker in the body. For URL forms where the refusing code differs between Jahia versions.
 */
export const expectNotServed = (path: string, host: string, extraHeaders: Headers = {}): void => {
    rawGet(path, host, extraHeaders).then(response => {
        const request = describeRequest(path, host, extraHeaders);
        expect(response.status, `${request} -> foreign content served with 200`).to.not.eq(HTTP_OK);
        expectNoForeignContent(response, request);
    });
};

/** Points a hostname at {@link SITE_KEY}. */
export const setSiteServerName = (serverName: string): void => {
    editSite(SITE_KEY, {serverName});
};

/**
 * Reads {@link SITE_KEY}'s current `j:serverName`, failing loudly when it cannot be read, so that
 * the value restored in after() is always the real one and never a guess.
 */
export const readSiteServerName = (): Cypress.Chainable<string> => {
    cy.login();
    return cy
        .apollo({query: getSiteServerName, variables: {path: SITE_PATH}})
        .then((result: JcrResult<{jcr?: {nodeByPath?: {serverName?: {value?: string}}}}>) => {
            const value = result?.data?.jcr?.nodeByPath?.serverName?.value;
            if (!value) {
                throw new Error(
                    `Cannot read j:serverName of ${SITE_PATH} (${JSON.stringify(result?.errors || result?.data)}); ` +
                        'refusing to continue, as after() could not restore it'
                );
            }

            return value;
        });
};

const listSiteNames = (workspace: 'EDIT' | 'LIVE'): Cypress.Chainable<string[]> =>
    cy
        .apollo({query: getSiteNames, variables: {workspace}})
        .then((result: JcrResult<{jcr?: {nodesByQuery?: {nodes?: {name: string}[]}}}>) => {
            const nodes = result?.data?.jcr?.nodesByQuery?.nodes;
            if (!nodes) {
                throw new Error(`Cannot list sites in ${workspace}: ${JSON.stringify(result?.errors || result)}`);
            }

            return nodes.map(node => node.name);
        });

/** Derives markers from the foreign page, dropping any that an ordinary 404 page also contains. */
const deriveMarkers = (page: string, notFoundPage: string): string[] => {
    const markers: string[] = [];
    const title = /<title[^>]*>[^<]*\S[^<]*<\/title>/i.exec(page);
    if (title) {
        markers.push(title[0]);
    }

    // A resource reference unique to the site; page URLs are skipped as an error page may echo them.
    const references = page.match(new RegExp(`/sites/${FOREIGN_SITE_KEY}/[^"'\\s<>?)]+`, 'g')) || [];
    const asset = references.find(reference => !reference.endsWith('.html'));
    if (asset) {
        markers.push(asset);
    }

    return markers.filter(marker => !notFoundPage.includes(marker));
};

const probeExists = (workspace: 'EDIT' | 'LIVE'): Cypress.Chainable<boolean> =>
    cy
        .apollo({
            query: findNodes,
            variables: {
                workspace,
                query: `select * from [jnt:file] where ischildnode('${FOREIGN_FILES_PATH}') and localname() = '${PROBE_NAME}'`
            }
        })
        .then((result: JcrResult<{jcr?: {nodesByQuery?: {nodes?: unknown[]}}}>) => {
            const nodes = result?.data?.jcr?.nodesByQuery?.nodes;
            if (!nodes) {
                throw new Error(`Cannot look up ${FOREIGN_PROBE_PATH} in ${workspace}: ${JSON.stringify(result)}`);
            }

            return nodes.length > 0;
        });

/** Uploads and publishes the probe file where missing, then waits until /files/live serves it. */
const ensureProbeFile = (): void => {
    probeExists('EDIT').then(inEdit => {
        if (!inEdit) {
            cy.log(`Uploading ${FOREIGN_PROBE_PATH}`);
            uploadFile(PROBE_FIXTURE, FOREIGN_FILES_PATH, PROBE_NAME, 'text/plain');
        }
    });
    probeExists('LIVE').then(inLive => {
        if (!inLive) {
            publishAndWaitJobEnding(FOREIGN_PROBE_PATH, ['en']);
        }
    });
    cy.fixture(PROBE_FIXTURE).then((content: string) => {
        foreignFileMarkers = [content.trim()];
    });

    let lastStatus = 'none';
    cy.waitUntil(
        () =>
            rawGet(foreignFileUrl, FOREIGN_SERVER_NAME).then(response => {
                lastStatus = String(response.status);
                return response.status === HTTP_OK && response.body.includes(foreignFileMarkers[0]);
            }),
        {
            timeout: SITE_CREATION_TIMEOUT_MS,
            interval: 2000,
            errorMsg: () =>
                `${describeRequest(foreignFileUrl, FOREIGN_SERVER_NAME)} last returned ${lastStatus}, wanted 200 ` +
                'with the probe file content; without it the file assertions would be vacuous'
        }
    );
};

/**
 * Makes sure the second site exists, is published, and serves its home page on its own hostname;
 * then derives the foreign-content markers from that page.
 *
 * Existence is checked separately (GraphQL as root), so a reused instance never re-creates the
 * site. The site is deliberately not deleted afterwards; the CI container is thrown away.
 */
export const ensureForeignSite = (): void => {
    cy.login();
    listSiteNames('EDIT').then(editSites => {
        if (editSites.includes(FOREIGN_SITE_KEY)) {
            return;
        }

        cy.log(`Creating the second site '${FOREIGN_SITE_KEY}'`);
        createSite(FOREIGN_SITE_KEY, {
            languages: 'en',
            templateSet: FOREIGN_TEMPLATE_SET,
            serverName: FOREIGN_SERVER_NAME,
            locale: 'en'
        });
    });
    // "Published" is judged by the page the suite depends on, not by the site node: createSite()
    // publishes the bare site node itself, so the site is "in live" while its pages are not.
    rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).then(live => {
        if (live.status !== HTTP_OK) {
            cy.log(`Publishing ${FOREIGN_SITE_PATH} (home page returned ${live.status})`);
            publishAndWaitJobEnding(FOREIGN_SITE_PATH, ['en']);
        }
    });

    ensureProbeFile();

    let lastHomeStatus = 'none';
    cy.waitUntil(
        () =>
            rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).then(live => {
                lastHomeStatus = String(live.status);
                return live.status === HTTP_OK;
            }),
        {
            timeout: SITE_CREATION_TIMEOUT_MS,
            interval: 2000,
            errorMsg: () =>
                `${describeRequest(
                    foreignRenderUrl,
                    FOREIGN_SERVER_NAME
                )} last returned ${lastHomeStatus}, wanted 200; ` +
                'without content there the cross-site assertions could not tell a block from an empty site'
        }
    );

    rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).then(page => {
        const missing = '/cms/render/live/en/sites/no-such-site-for-markers/home.html';
        rawGet(missing, FOREIGN_SERVER_NAME).then(notFound => {
            foreignMarkers = deriveMarkers(page.body, notFound.body);
            if (foreignMarkers.length === 0) {
                throw new Error(
                    `No usable marker in ${describeRequest(foreignRenderUrl, FOREIGN_SERVER_NAME)}; ` +
                        'the body assertions would be vacuous'
                );
            }
        });
    });
};

/**
 * Blocks until the server-name -> site mapping is live.
 *
 * `JahiaSitesService` caches the lookup, so the mutation is not observable on the very next
 * request. Rather than sleeping, this polls the filter's own behaviour: once the foreign site is
 * answered 404 on {@link SITE_SERVER_NAME}, the mapping has taken effect.
 */
export const waitForMappingToApply = (): void => {
    let lastStatus = 'none';
    cy.waitUntil(
        () =>
            rawGet(foreignRenderUrl, SITE_SERVER_NAME).then(response => {
                lastStatus = String(response.status);
                return response.status === HTTP_NOT_FOUND;
            }),
        {
            timeout: MAPPING_TIMEOUT_MS,
            interval: 1000,
            errorMsg: () =>
                `Site ${SITE_KEY} never became reachable as ${SITE_SERVER_NAME}: ` +
                `${describeRequest(foreignRenderUrl, SITE_SERVER_NAME)} last returned ${lastStatus}, wanted 404. ` +
                'CrossSitesFilter is not enforcing, so the suite would pass vacuously'
        }
    );
};

/**
 * Logs in and returns the session cookie header, after proving the session is authenticated:
 * an edit-mode page answers 200 with the cookie and not 200 without it. (Guests get a JSESSIONID
 * too, so the cookie's existence proves nothing.)
 */
export const sessionCookieHeader = (): Cypress.Chainable<Headers> => {
    cy.login();
    cy.visit('/jahia/dashboard');
    return cy.getCookie('JSESSIONID').then(cookie => {
        if (!cookie) {
            throw new Error('No JSESSIONID after login; the authenticated assertions would run as guest');
        }

        const headers: Headers = {Cookie: `JSESSIONID=${cookie.value}`};
        rawGet(AUTH_PROBE_URL, UNMAPPED_SERVER_NAME).then(guest => {
            expect(guest.status, `${describeRequest(AUTH_PROBE_URL, UNMAPPED_SERVER_NAME)} without cookie`).to.not.eq(
                HTTP_OK
            );
        });
        expectStatus(AUTH_PROBE_URL, UNMAPPED_SERVER_NAME, HTTP_OK, headers);
        return cy.wrap(headers);
    });
};
