import {defineConfig} from 'cypress';
import fs from 'fs';

// Every assertion in this suite depends on the Host header Jahia sees, so the host is a
// first-class setting rather than something baked into baseUrl. It must never be
// localhost/127.0.0.1 - see .env.example for why.
const jahiaHost = process.env.JAHIA_HOST || 'jahia';
const jahiaPort = process.env.JAHIA_PORT || '8080';

export default defineConfig({
    reporter: 'cypress-multi-reporters',
    reporterOptions: {
        configFile: 'reporter-config.json'
    },
    screenshotsFolder: './results/screenshots',
    video: true, // In Cypress, videos are disabled by default
    videosFolder: './results/videos',
    viewportWidth: 1366,
    viewportHeight: 768,
    watchForFileChanges: false,
    e2e: {
        setupNodeEvents(on, config) {
            // Delete videos for tests that did not fail
            on(
                'after:spec',
                (spec: Cypress.Spec, results: CypressCommandLine.RunResult) => {
                    if (results && results.video) {
                        // Do we have failures for any retry attempts?
                        const failures = results.tests.some(test =>
                            test.attempts.some(attempt => attempt.state === 'failed')
                        );
                        if (!failures) {
                            // Delete the video if the spec passed and no tests retried
                            fs.unlinkSync(results.video);
                        }
                    }
                }
            );
            // eslint-disable-next-line @typescript-eslint/no-var-requires
            return require('./cypress/plugins/index.js')(on, config);
        },
        excludeSpecPattern: '*.ignore.ts',
        baseUrl: `http://${jahiaHost}:${jahiaPort}`
    },
    env: {
        JAHIA_HOST: jahiaHost,
        JAHIA_PORT: jahiaPort
    }
});
