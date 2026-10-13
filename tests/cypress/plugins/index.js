/// <reference types="cypress" />
// ***********************************************************
// Cypress node-side plugins.
// ***********************************************************

const http = require('http');

const REQUEST_TIMEOUT_MS = 30000;

/**
 * Issues an HTTP request whose request-target is sent to the server byte for byte.
 *
 * `cy.request` (and anything else built on WHATWG URL parsing) resolves `..` segments and
 * re-encodes percent escapes before the bytes ever hit the socket. That is exactly the
 * transformation this module's filter is being tested against, so using it would quietly
 * rewrite the payload and make the bypass specs assert nothing. Node's `http.request` passes
 * `options.path` through untouched, which is why the suite goes through this task instead.
 *
 * @param {{host: string, port: number|string, path: string, headers?: Record<string,string>, method?: string}} options request description
 * @returns {Promise<{status: number, headers: Record<string,string|string[]>, body: string}>} the response
 */
const rawRequest = ({host, port, path, headers = {}, method = 'GET'}) => new Promise((resolve, reject) => {
    const request = http.request({host, port, path, method, headers}, response => {
        let body = '';
        response.setEncoding('utf8');
        response.on('data', chunk => {
            body += chunk;
        });
        response.on('end', () => resolve({
            status: response.statusCode,
            headers: response.headers,
            body
        }));
    });

    request.setTimeout(REQUEST_TIMEOUT_MS, () => {
        request.destroy(new Error(`rawRequest timed out after ${REQUEST_TIMEOUT_MS}ms: ${method} http://${host}:${port}${path}`));
    });

    request.on('error', error => {
        reject(new Error(`rawRequest failed for ${method} http://${host}:${port}${path}: ${error.message}`));
    });

    request.end();
});

/**
 * @type {Cypress.PluginConfig}
 */
module.exports = (on, config) => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    require('cypress-terminal-report/src/installLogsPrinter')(on);
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    require('@jahia/cypress/dist/plugins/registerPlugins').registerPlugins(on, config);

    on('task', {rawRequest});

    return config;
};
