import {DocumentNode} from 'graphql';
import gql from 'graphql-tag';
import {createSite, deleteNode, editSite, publishAndWaitJobEnding, uploadFile} from '@jahia/cypress';

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
 * Files the suite uploads (all from the fixture `cypress/fixtures/crossSitesProbe.txt`), so that
 * file URLs have real content to withhold or to serve. A fresh site has no files of its own.
 */
const PROBE_FIXTURE = 'crossSitesProbe.txt';
const PROBE_NAME = 'crossSitesProbe.txt';
const SHARED_PROBE_NAME = 'crossSitesSharedProbe.txt';
const FOREIGN_FILES_PATH = `${FOREIGN_SITE_PATH}/files`;
const FOREIGN_PROBE_PATH = `${FOREIGN_FILES_PATH}/${PROBE_NAME}`;
const SHARED_FILES_PATH = '/sites/systemsite/files';
export const foreignFileUrl = `/files/live${FOREIGN_PROBE_PATH}`;
export const foreignDefaultFileUrl = `/files/default${FOREIGN_PROBE_PATH}`;
/** Path of the probe file the foreign site holds, as a function of how its key is spelled. */
export const foreignFileUrlWithKey = (key: string): string => `/files/live/sites/${key}/files/${PROBE_NAME}`;
/** A real file in systemsite, whose content every site may be served. */
export const sharedFileUrl = `/files/live${SHARED_FILES_PATH}/${SHARED_PROBE_NAME}`;

interface Probe {
    name: string;
    folderPath: string;
    /** Host the live file is checked on: the owning site's own hostname, or any unguarded one. */
    liveHost: string;
}

const FOREIGN_PROBE: Probe = {name: PROBE_NAME, folderPath: FOREIGN_FILES_PATH, liveHost: FOREIGN_SERVER_NAME};
const SHARED_PROBE: Probe = {name: SHARED_PROBE_NAME, folderPath: SHARED_FILES_PATH, liveHost: UNMAPPED_SERVER_NAME};

/**
 * A default-workspace page: a guest is answered 404 (measured on every Jahia version tested), only
 * an authenticated user gets 200.
 * Not /cms/edit/...: on Jahia 8.2 that legacy URL redirects EVERYONE, guest included, to
 * /jahia/page-composer/..., so its status says nothing about the session.
 */
const AUTH_PROBE_URL = `/cms/render/default/en${SITE_PATH}/home.html`;

const HTTP_OK = 200;
const HTTP_NOT_FOUND = 404;
const HTTP_SERVER_ERROR = 500;
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

/** Content of the uploaded probe files, read from the fixture by {@link ensureForeignSite}. */
let foreignFileMarkers: string[] = [];

/** A static module asset the foreign page references (no /sites/ segment), found by {@link ensureForeignSite}. */
let staticAssetUrl = '';

export const getStaticAssetUrl = (): string => {
    if (!staticAssetUrl) {
        throw new Error('No static asset URL available; call ensureForeignSite() in before() first');
    }

    return staticAssetUrl;
};

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

    return assertUsableMarkers(foreignMarkers);
};

/** Fails loudly on an empty marker: `includes('')` is always true, so it would make a check meaningless. */
const assertUsableMarkers = (markers: string[]): string[] => {
    if (markers.length === 0 || markers.some(marker => typeof marker !== 'string' || marker.trim() === '')) {
        throw new Error(`Unusable marker list ${JSON.stringify(markers)}: markers must be non-blank strings`);
    }

    return markers;
};

const getFileMarker = (): string => assertUsableMarkers(foreignFileMarkers)[0];

const expectNoForeignContent = (response: RawResponse, request: string, markers: string[] = getMarkers()): void => {
    assertUsableMarkers(markers).forEach(marker => {
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

/** Asserts `path` is served (200) on `host` with the probe file's content in the body. */
export const expectFileServed = (path: string, host: string, extraHeaders: Headers = {}): void => {
    expectStatus(path, host, HTTP_OK, extraHeaders).then(response => {
        expect(
            response.body.includes(getFileMarker()),
            `${describeRequest(path, host, extraHeaders)} -> 200 but the body lacks the probe file content`
        ).to.eq(true);
    });
};

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
            const detail =
                `${describeRequest(path, host, extraHeaders)} -> ${response.status}, but ` +
                `${describeRequest(path, baselineHost, extraHeaders)} -> ${baseline.status}`;
            // A shared 5xx is a failure, not parity.
            expect(baseline.status, `${detail} (baseline is a server error)`).to.be.lessThan(HTTP_SERVER_ERROR);
            expect(response.status, `${detail} (server error)`).to.be.lessThan(HTTP_SERVER_ERROR);
            expect(response.status, detail).to.eq(baseline.status);
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
 * Authoring URLs are not enforced: `host` must get exactly what `baselineHost` gets (status, and no
 * foreign content in the body), whatever that status is. Meant for guests, where the answer is a
 * 404 or a redirect on both hosts.
 */
export const expectSameAsOwnHostWithoutLeak = (
    path: string,
    host: string,
    baselineHost: string,
    extraHeaders: Headers = {}
): void => {
    expectSameStatusAs(path, host, baselineHost, extraHeaders).then(response => {
        expectNoForeignContent(response, describeRequest(path, host, extraHeaders), [
            ...getMarkers(),
            ...foreignFileMarkers
        ]);
    });
};

/** The 404 must be Jahia's rendered error page, not a bare or empty one (e.g. the error page itself re-blocked). */
const expectRenderedErrorPage = (response: RawResponse, request: string): void => {
    const contentType = String(response.headers['content-type'] || '');
    expect(contentType.toLowerCase(), `${request} -> 404 is not text/html (content-type: ${contentType})`).to.include(
        'text/html'
    );
    expect(response.body.trim().length, `${request} -> 404 body is empty`).to.be.greaterThan(0);
    expect(response.body, `${request} -> 404 body has no <title>; not a rendered error page`).to.match(/<title/i);
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
            expectRenderedErrorPage(response, request);
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

/**
 * Positive-control variant for URL forms the platform may refuse itself (it differs by Jahia
 * version). Requests the URL on an unguarded host: when the platform serves the foreign content
 * there, the mapped host MUST withhold it (404, no markers); when the platform refuses it, that is
 * logged and the property "foreign content not served" is kept.
 */
export const expectWithheldWhereServed = (path: string, host: string): void => {
    rawGet(path, UNMAPPED_SERVER_NAME).then(control => {
        const servedByPlatform = control.status === HTTP_OK && getMarkers().some(m => control.body.includes(m));
        if (!servedByPlatform) {
            cy.log(
                `Platform itself refuses ${describeRequest(path, UNMAPPED_SERVER_NAME)} (${control.status}); ` +
                    'asserting only that foreign content is not served'
            );
            expectNotServed(path, host);
            return;
        }

        rawGet(path, host).then(response => {
            const request = describeRequest(path, host);
            expect(response.status, `${request} -> expected 404; the unguarded host serves the foreign page`).to.eq(
                HTTP_NOT_FOUND
            );
            expectNoForeignContent(response, request);
        });
    });
};

/**
 * A search on `onSite`'s search-results page (present on sites built from {@link FOREIGN_TEMPLATE_SET})
 * whose scope is `selectedSites`, the value of the search module's `src_sites.values` parameter.
 * The term matches content of the demo site {@link SITE_KEY}.
 */
export const searchUrl = (onSite: string, selectedSites: string): string =>
    `/cms/render/live/en/sites/${onSite}/home/search-results.html?` +
    [
        'src_terms%5B0%5D.term=company',
        'src_terms%5B0%5D.match=any_word',
        'src_terms%5B0%5D.fields.siteContent=true',
        'src_terms%5B0%5D.fields.files=true',
        'src_languages.values=en',
        `src_sites.values=${selectedSites}`
    ].join('&');

/**
 * The term search of {@link searchUrl} on `onSite`, scoped by the raw parameter `scope` alone
 * (for example `src_pagePath.value=/sites/x`), to compare the spellings Jahia applies as a scope.
 */
export const termSearchUrl = (onSite: string, scope: string): string =>
    `/cms/render/live/en/sites/${onSite}/home/search-results.html?` +
    [
        'src_terms%5B0%5D.term=company',
        'src_terms%5B0%5D.match=any_word',
        'src_terms%5B0%5D.fields.siteContent=true',
        'src_terms%5B0%5D.fields.files=true',
        'src_languages.values=en',
        scope
    ].join('&');

/**
 * A search on `onSite`'s search-results page that has no term, only a node-property criterion
 * (`src_properties(jnt:news).desc`), matching the news of the demo site {@link SITE_KEY}. Without
 * `selectedSites` it sets no site scope at all, which Jahia searches as the whole repository.
 */
export const nodePropertySearchUrl = (onSite: string, selectedSites?: string): string =>
    `/cms/render/live/en/sites/${onSite}/home/search-results.html?` +
    [
        'src_languages.values=en',
        'src_properties(jnt:news).desc.value=company',
        'src_properties(jnt:news).desc.match=any_word',
        ...(selectedSites ? [`src_sites.values=${selectedSites}`] : [])
    ].join('&');

const siteUuidQuery = gql`
    query siteUuid($path: String!) {
        jcr(workspace: LIVE) {
            nodeByPath(path: $path) {
                uuid
            }
        }
    }
`;

/** The JCR identifier of a live node, i.e. what the renderer's `jsite` and `jarea` parameters carry. */
export const readNodeUuid = (path: string): Cypress.Chainable<string> => {
    cy.login();
    return cy
        .apollo({query: siteUuidQuery, variables: {path}})
        .then((result: JcrResult<{jcr?: {nodeByPath?: {uuid?: string}}}>) => {
            const uuid = result?.data?.jcr?.nodeByPath?.uuid;
            if (!uuid) {
                throw new Error(`Cannot read the identifier of ${path}: ${JSON.stringify(result)}`);
            }

            return uuid;
        });
};

/** The JCR identifier of a site node, i.e. what the renderer's `jsite` parameter carries. */
export const readSiteUuid = (siteKey: string): Cypress.Chainable<string> => readNodeUuid(`/sites/${siteKey}`);

/**
 * A live node of {@link SITE_KEY} that renders markup naming its site path when Jahia's
 * TemplateAjaxFilter renders it for `jarea` (the events tab of the digitall 3.0.0 home page).
 */
export const FOREIGN_AREA_NODE_PATH = `${SITE_PATH}/home/area-main/area-2/area-2-side/event-tab-query`;

/** `page`'s `.html.ajax` URL, with `jarea` set to `areaUuid` when given. */
export const ajaxUrl = (pagePath: string, areaUuid?: string): string =>
    `/cms/render/live/en${pagePath}.html.ajax${areaUuid ? `?jarea=${areaUuid}` : ''}`;

/**
 * Asserts a request is refused with Jahia's rendered 404 page and none of `markers`, without
 * comparing to an unguarded host: for a selection the module refuses on every hostname, whose
 * positive control is the same node rendered legitimately on its own site's page.
 */
export const expectRefused = (path: string, host: string, markers: string[]): void => {
    assertUsableMarkers(markers);
    const request = describeRequest(path, host);
    rawGet(path, host).then(response => {
        expect(response.status, `${request} -> expected 404 (refused)`).to.eq(HTTP_NOT_FOUND);
        expectRenderedErrorPage(response, request);
        expectNoForeignContent(response, request, markers);
    });
};

/** `href` attributes of `page` that `reference` lacks, leaving out links to the unguarded host itself. */
const hrefsOnlyIn = (page: string, reference: string): string[] => {
    const known = reference.match(/href="[^"]*"/g) || [];
    const found = page.match(/href="[^"]*"/g) || [];
    return found.filter(
        (href, index) => found.indexOf(href) === index && !known.includes(href) && !href.includes(UNMAPPED_SERVER_NAME)
    );
};

/**
 * {@link expectBlocked} for a site chosen by a request parameter while the path names the hostname's
 * own site. On the unguarded host, `path` must serve links that `controlPath` (the same page without
 * the foreign selection) does not: those links are the other site's content, and the markers. Over
 * `host` the same request must then be withheld.
 */
export const expectSelectionBlocked = (path: string, controlPath: string, host: string): void => {
    rawGet(controlPath, UNMAPPED_SERVER_NAME).then(control => {
        expectStatus(path, UNMAPPED_SERVER_NAME, HTTP_OK).then(unguarded => {
            const markers = hrefsOnlyIn(unguarded.body, control.body);
            expect(
                markers.length,
                `${describeRequest(path, UNMAPPED_SERVER_NAME)} serves no link that ` +
                    `${describeRequest(controlPath, UNMAPPED_SERVER_NAME)} lacks; nothing to withhold`
            ).to.be.greaterThan(0);
            expectBlocked(path, host, {}, markers);
        });
    });
};

/**
 * For pages that name no site in their path, such as the /sites root listing, whose content differs
 * by Jahia version (some render each site's server name, others only keys, titles or node links).
 *
 * `markers` are every form in which the foreign site may be revealed. The unguarded host is asked
 * first: the markers it actually carries are the ones the block must withhold, with the full
 * {@link expectBlocked} check (404 rendered page, no marker, body differs). When the platform
 * reveals none of them, that is logged and the weaker property is kept: the mapped host must not
 * serve any marker.
 */
export const expectServedUnguardedThenBlocked = (path: string, host: string, markers: string[]): void => {
    assertUsableMarkers(markers);
    expectStatus(path, UNMAPPED_SERVER_NAME, HTTP_OK).then(unguarded => {
        const revealing = markers.filter(marker => unguarded.body.includes(marker));
        if (revealing.length > 0) {
            cy.log(`${path} reveals ${JSON.stringify(revealing)} on an unguarded host; asserting it is withheld`);
            expectBlocked(path, host, {}, revealing);
            return;
        }

        cy.log(
            `${describeRequest(path, UNMAPPED_SERVER_NAME)} reveals none of ${JSON.stringify(markers)} on this ` +
                'Jahia version; asserting only that the mapped host serves none of them'
        );
        rawGet(path, host).then(response => {
            expectNoForeignContent(response, describeRequest(path, host), markers);
        });
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

            if (value === SITE_SERVER_NAME) {
                throw new Error(
                    `${SITE_PATH} already has j:serverName '${SITE_SERVER_NAME}': an earlier run aborted before ` +
                        'its after() restored the original. Reset j:serverName by hand (e.g. to localhost) and rerun'
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

const probeExists = (workspace: 'EDIT' | 'LIVE', probe: Probe): Cypress.Chainable<boolean> =>
    cy
        .apollo({
            query: findNodes,
            variables: {
                workspace,
                query: `select * from [jnt:file] where ischildnode('${probe.folderPath}') and localname() = '${probe.name}'`
            }
        })
        .then((result: JcrResult<{jcr?: {nodesByQuery?: {nodes?: unknown[]}}}>) => {
            const nodes = result?.data?.jcr?.nodesByQuery?.nodes;
            if (!nodes) {
                throw new Error(
                    `Cannot look up ${probe.folderPath}/${probe.name} in ${workspace}: ${JSON.stringify(result)}`
                );
            }

            return nodes.length > 0;
        });

/**
 * Makes sure `probe` is live with the fixture's exact content: when /files/live does not serve it
 * (missing, unpublished or stale), it is replaced, uploaded again and published; then waits for 200.
 */
const ensureProbeFile = (probe: Probe): void => {
    const path = `${probe.folderPath}/${probe.name}`;
    const liveUrl = `/files/live${path}`;

    cy.fixture(PROBE_FIXTURE).then((content: string) => {
        const marker = assertUsableMarkers([content.trim()])[0];
        foreignFileMarkers = [marker];

        rawGet(liveUrl, probe.liveHost).then(live => {
            if (live.status === HTTP_OK && live.body.includes(marker)) {
                return;
            }

            cy.log(`Uploading ${path} (live returned ${live.status})`);
            probeExists('EDIT', probe).then(inEdit => {
                if (inEdit) {
                    deleteNode(path);
                }

                uploadFile(PROBE_FIXTURE, probe.folderPath, probe.name, 'text/plain');
                publishAndWaitJobEnding(path, ['en']);
            });
        });

        let lastStatus = 'none';
        cy.waitUntil(
            () =>
                rawGet(liveUrl, probe.liveHost).then(response => {
                    lastStatus = String(response.status);
                    return response.status === HTTP_OK && response.body.includes(marker);
                }),
            {
                timeout: SITE_CREATION_TIMEOUT_MS,
                interval: 2000,
                errorMsg: () =>
                    `${describeRequest(liveUrl, probe.liveHost)} last returned ${lastStatus}, wanted 200 ` +
                    'with the probe file content; without it the file assertions would be vacuous'
            }
        );
    });
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
    // Idempotent on a reused instance. Done BEFORE the probes below, which request the site on
    // FOREIGN_SERVER_NAME: against a stale mapping they would republish or re-upload on every run.
    editSite(FOREIGN_SITE_KEY, {serverName: FOREIGN_SERVER_NAME});
    // "Published" is judged by the page the suite depends on, not by the site node: createSite()
    // publishes the bare site node itself, so the site is "in live" while its pages are not.
    rawGet(foreignRenderUrl, FOREIGN_SERVER_NAME).then(live => {
        if (live.status !== HTTP_OK) {
            cy.log(`Publishing ${FOREIGN_SITE_PATH} (home page returned ${live.status})`);
            publishAndWaitJobEnding(FOREIGN_SITE_PATH, ['en']);
        }
    });

    ensureProbeFile(FOREIGN_PROBE);
    ensureProbeFile(SHARED_PROBE);

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
            const asset = /\/modules\/[^"'\s<>?)]+\.(?:css|js)/.exec(page.body);
            if (!asset) {
                throw new Error(
                    `No /modules/...css|js reference in ${describeRequest(foreignRenderUrl, FOREIGN_SERVER_NAME)}; ` +
                        'cannot pick a static asset for the no-/sites/-segment assertion'
                );
            }

            staticAssetUrl = asset[0];
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
 * a default-workspace page answers 200 with the cookie and 404 without it. (Guests get a JSESSIONID
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
            expect(
                guest.status,
                `${describeRequest(AUTH_PROBE_URL, UNMAPPED_SERVER_NAME)} without cookie: a guest gets 404 (measured)`
            ).to.eq(HTTP_NOT_FOUND);
        });
        expectStatus(AUTH_PROBE_URL, UNMAPPED_SERVER_NAME, HTTP_OK, headers);
        return cy.wrap(headers);
    });
};
