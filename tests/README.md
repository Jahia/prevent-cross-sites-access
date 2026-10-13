# Tests

End-to-end tests for `prevent-cross-sites-access`, using the standard `@jahia/cypress` Docker harness.

The module is a servlet filter that rejects a request when the site key in the URL path does not
match the site that the request's `Host` header resolves to. Everything here is built around
controlling those two inputs independently.

## Build the module first

The harness deploys whatever it finds in `tests/artifacts`, which `ci.build.sh` fills from
`../target`. Build the module before the first run, and after every Java change:

```bash
(cd .. && mvn clean install)
```

## Run all in Docker (what CI does)

```bash
bash ci.build.sh      # build the test image + stage ../target/*-SNAPSHOT.jar
bash ci.startup.sh    # boot Jahia + cypress, run the specs, exit with the suite status
```

Re-run `ci.build.sh` after **any** change under `tests/`, or the change never reaches the container.

## Run the specs against a local node

```bash
yarn                      # fetch JS deps on the host
./ci.startup.sh notests   # boot Jahia WITHOUT running the specs
./env.run.sh              # provision the environment + one headless run
source set-env.sh         # REQUIRED, and again in every new terminal
yarn run e2e:debug        # interactive runner (or `yarn e2e:ci` for headless)
```

`JAHIA_HOST` (default `jahia`) is only the TCP target. On a local node, either add
`127.0.0.1 jahia` to `/etc/hosts` or set `JAHIA_HOST=localhost` in `.env` — the specs send their
own `Host` header, so a `localhost` connection target does not weaken them.

## How the specs drive the filter

Both specs map a hostname to `digitall` with `editSite`, then make requests through the
`rawRequest` Cypress task (`cypress/plugins/index.js`) rather than `cy.request`. Two reasons:

- **The request-target is sent byte for byte.** `cy.request` resolves `..` segments and
  re-encodes percent escapes before anything reaches the socket — the exact transformation the
  filter is being tested against. Normalising the payload would make the bypass specs assert
  nothing while still passing.
- **`Host` is set independently of the connection target**, so the specs reach every branch of
  `ServerNameToSiteMapper.getSiteKeyByServerName()` (mapped site, unmapped host, `localhost`)
  with no DNS or compose aliases.

Requests use `another-site`, a site key that does not exist. The only possible answers are then
400 (the filter rejected it) or 404 (the renderer did not find it), which makes *which component
produced the status* provable instead of inferred.

Each spec restores `digitall`'s original `j:serverName` in `after()`.

## The two specs

| Spec | Expected state |
|---|---|
| `01-crossSitesAccess.cy.ts` | **green** — the behaviour the module is meant to have |
| `02-crossSitesBypasses.cy.ts` | **red** — defects found by code review, encoded as the behaviour that *should* hold |

`02` is deliberately failing: it is the TDD red state for the filter's URL-matching defects
(raw `getRequestURI()` vs the renderer's decoded, normalised `getPathInfo()`; `substringBetween`
returning null when the site key ends the path; `systemsite` shared content being treated as a
foreign site). The file header documents each one.

To get a green run before those are fixed, scope the run to the first spec:

```bash
yarn e2e:ci --spec "cypress/e2e/01-crossSitesAccess.cy.ts"
```

Do not delete tests from `02` to make CI green — fix the filter, or change an assertion
deliberately if you disagree with the expectation it encodes.

## Reports

- `yarn report:merge` — merge per-spec mochawesome JSON into `results/reports/report.json`
- `yarn report:html` — render the HTML report into `results/reports/`

Videos are kept only for failing specs.
