# Tests

This directory holds the end-to-end suite for `prevent-cross-sites-access`, built on the standard `@jahia/cypress` Docker harness. Unit tests live in `src/test/java` and run from the repository root with `mvn clean install` (no Docker needed).

The module is a servlet filter that answers 404 when the site a request path addresses differs from the site its hostname maps to. Every spec is built around controlling those two inputs independently.

## Prerequisites

- **Docker** with the Compose plugin (`docker compose`).
- **A Jahia license**, supplied as `JAHIA_LICENSE` in `tests/.env`. That file is git-ignored and excluded from the test image by `.dockerignore`; the license is never baked into an image.
- **Maven and a JDK**, to build the module jar.
- **Node.js and Yarn 1** (the package manager is pinned to `yarn@1.22.22`), only for local runs against a node. The Docker image installs its own dependencies.
- **Registry access** to the Jahia image you choose, and the Nexus credentials described below.

## Environment file and variables

Create `tests/.env`. `set-env.sh` loads it; if it is missing, the values from `tests/.env.example` are used instead. Do not commit `tests/.env`.

| Variable | Read by | Purpose |
|---|---|---|
| `JAHIA_IMAGE` | `docker-compose.yml` (`jahia` service) | Jahia image under test, e.g. `ghcr.io/jahia/jahia-ee-dev:8.2.3.2` |
| `JAHIA_LICENSE` | `docker-compose.yml` (`jahia` service) | Jahia license |
| `SUPER_USER_PASSWORD` | `docker-compose.yml` (both services) | Password of the Jahia super user the specs log in as |
| `TESTS_IMAGE` | `docker-compose.yml` (`cypress` service) | Name of the Cypress test image built by `ci.build.sh` |
| `MANIFEST` | `docker-compose.yml` (`cypress` service) | Provisioning manifest handed to the harness |
| `JAHIA_HOST` | `docker-compose.yml`, `cypress/support/crossSites.ts` | TCP target of the specs; defaults to `jahia` when empty or unset |
| `JAHIA_PORT` | `docker-compose.yml`, `cypress/support/crossSites.ts` | TCP port of the specs; defaults to `8080` |
| `NEXUS_USERNAME`, `NEXUS_PASSWORD` | `docker-compose.yml` (`cypress` service) | Passed through to the Cypress container |

Set by the compose file itself: `JAHIA_URL=http://jahia:8080` and `JPDA=true` (JDWP debugging on port 8000). `ci.startup.sh` also prints `JAHIA_CLUSTER_ENABLED` and `MODULE_ID`; neither is needed by the compose file.

Host ports are bound to loopback only: `127.0.0.1:8080` (Jahia HTTP) and `127.0.0.1:8000` (JDWP debug).

## Build the module first

`ci.build.sh` deploys whatever it finds in `tests/artifacts`, which it fills from `../target`. It stops with an error if no `*-SNAPSHOT.jar` is in `../target`, and it deletes stale `*-SNAPSHOT` artifacts before copying, so the container only ever gets the module built from the checked-out source.

```bash
(cd .. && mvn clean install)
```

Rebuild the jar after every Java change.

## Choosing the Jahia image

The image is selected with `JAHIA_IMAGE`. The suite has been run against three images:

- `8.2.3.2`
- `8.2.3.7`
- the default `8-SNAPSHOT` image

Use **8.2.3.2 as the reference target**. On that release Jahia core has no `MalformedRequestFilter`, so double-encoded and non-normalised URLs reach the module. A green run there therefore shows that the module itself refuses them. On 8.2.3.7 and 8-SNAPSHOT core rejects some of those URLs with 400 before the module runs; the specs still pass there, but for those forms they no longer prove anything about the module. The assertion is written as a property ("foreign content is not served"), so it holds whichever layer refuses.

```bash
JAHIA_IMAGE=ghcr.io/jahia/jahia-ee-dev:8.2.3.2 bash ci.startup.sh
```

## Run all in Docker (what CI does)

```bash
bash ci.build.sh      # build the test image and stage ../target/*-SNAPSHOT.jar
bash ci.startup.sh    # boot Jahia and Cypress, run the specs, exit with the suite status
```

Re-run `ci.build.sh` after **any** change under `tests/`, or the change never reaches the container.

To boot Jahia without running the specs, for instance to poke at it by hand:

```bash
./ci.startup.sh notests
```

## Run the specs against a local node

```bash
yarn                      # fetch JS deps on the host
./ci.startup.sh notests   # boot Jahia WITHOUT running the specs
./env.run.sh              # provision the environment and run the suite once, headless
source set-env.sh         # REQUIRED, and again in every new terminal
yarn run e2e:debug        # interactive runner (or `yarn e2e:ci` for headless)
```

`JAHIA_HOST` (default `jahia`) is only the TCP target. On a local node either add `127.0.0.1 jahia` to `/etc/hosts`, or set `JAHIA_HOST=localhost` in `tests/.env`. The specs send their own `Host` header, so a `localhost` connection target does not weaken them.

`yarn lint` runs ESLint over the TypeScript specs.

## How the specs drive the filter

Both specs map a hostname to `digitall` with `editSite`, then send requests through the `rawRequest` Cypress task (`cypress/plugins/index.js`) rather than `cy.request`. Two reasons:

- **The request-target is sent byte for byte.** `cy.request` resolves `..` segments and re-encodes percent escapes before anything reaches the socket, which is exactly the transformation the filter is tested against. Normalising the payload would make the bypass specs assert nothing while still passing.
- **`Host` is set independently of the connection target.** The specs reach every branch of the hostname lookup (mapped site, unmapped host, `localhost`) with no DNS or compose aliases. The fixture hostnames `prevent-cross-sites.test`, `cross-sites-other.test` and `not-a-jahia-site.test` are only ever sent as `Host` headers.

### Two real sites: served versus withheld

Both specs run against two real sites:

- `digitall`, mapped to `prevent-cross-sites.test` for the duration of a spec;
- `crosssitesother`, the foreign site, served on `cross-sites-other.test`.

`ensureForeignSite()` in `cypress/support/crossSites.ts` creates the foreign site only if it is missing, publishes it only if it is not yet live, and uploads and publishes the probe file `crossSitesProbe.txt` so that file URLs have real content to withhold. It is deliberately not deleted afterwards; the CI container is discarded either way.

The markers used to detect foreign content are derived at runtime from the foreign home page (its `<title>` and one `/sites/crosssitesother/...` asset reference) and filtered against an ordinary 404 page. Nothing is hard-coded.

A made-up site key would prove nothing: Jahia resolves an unknown key to no site and answers 404 on its own, so "404 instead of content" would hold with the module uninstalled. With a real foreign site the comparison is **200 (served on an unguarded host) against 404 (withheld on the mapped host)**. `expectBlocked()` asserts three things: the same URL is 200 on the unmapped host, it is 404 on the mapped host, and the body contains no foreign marker and differs from the unguarded body.

Both specs restore `digitall`'s original `j:serverName` in `after()`, and both prove the session state they depend on before asserting.

## The two specs

### `01-crossSitesAccess.cy.ts`: the intended behaviour

Test names below are the exact `it()` titles, so a failing test can be looked up here by name.

| Group | Test (exact title) | What it asserts |
|---|---|---|
| Calibration | serves the second site's content on its own hostname | 200 and the foreign page's markers present: there is content to withhold |
| Enforcement | blocks another site's live content requested over the mapped hostname | 200 on an unguarded host, 404 on the mapped one, no foreign marker in the body |
| | blocks another site's live files requested over the mapped hostname | same, on a real uploaded probe file under `/files/live` |
| | blocks a trailing-dot hostname exactly like the plain mapped hostname | `prevent-cross-sites.test.` is treated as `prevent-cross-sites.test` |
| | blocks an upper-case hostname exactly like the plain mapped hostname | Host case does not matter |
| Own site | serves the mapped site's own content exactly as it would with no filter in play | same status as the unguarded host, plain, trailing-dot and upper-case forms |
| Non-site paths | ignores request paths that carry no /sites/ segment | same status as the unguarded host |
| Fail-open | does not block anything when the hostname maps to no site | foreign content served in full |
| | does not block anything when the hostname is localhost | foreign content served in full |
| Authoring | does not enforce authoring URLs (default workspace files) for another site | `/files/default/...` answers as on the foreign site's own host |
| Dot segments | cannot be bypassed with dot segments (core tripwire: Jahia core rejects them first) | mapped host: foreign content not served; unmapped host: exactly 400 (a layer before the module) |
| Authenticated editors | can still open edit mode for the site on its own hostname | 200, with a session proven to be authenticated |
| | are blocked from another site live content just like anonymous visitors | blocked, same as a guest |
| | are not blocked on another site's `<label>` URL over the mapped hostname | one test each for the edit frame, edit and default-workspace render URLs: same status as on the foreign site's own host |

The fail-open tests are asserted rather than hidden: the control is hostname-driven, so an unmapped host or `localhost` is served in full.

The dot-segment test is a tripwire. The request is refused with 400 on the unmapped host, where this module provably does nothing, so that 400 is produced by a layer before the module. The mapped-host assertion is "foreign content not served", which holds whichever layer refuses. If the platform ever stops screening traversal, both requests return 200 and the test reports that the module is newly exposed.

Unknown site keys and forms where the site key is the last path segment are not asserted over HTTP: they are 404 whether or not the filter acts. They are covered by the JUnit tests.

### `02-crossSitesBypasses.cy.ts`: former bypasses and false positives

These are regression tests for three defects, all fixed, plus one control. Both specs are expected to pass.

| Defect | Effect before the fix | Test (exact title) |
|---|---|---|
| The filter matched the raw request URI, not the decoded path the renderer uses | `/cms/render/live/en/%73ites/<foreign>/home.html` (`%73` is `s`) returned the foreign site's page | blocks a foreign site reached through a percent-encoded /sites/ segment |
| The filter ran on REQUEST only; Jahia's SEO inbound rule forwards and the forward decodes a second time | measured on 8.2.3.2: `/en/sites/%2564igitall/home.html` over another site's hostname returned digitall's full home page | does not serve a foreign site addressed with a double-encoded key in the SEO short form |
| `systemsite` was treated as a foreign site | shared content under `systemsite` failed on every site using it | still serves cross-site shared content stored under systemsite |
| (control) | a filter that blocked everywhere would satisfy every test above | leaves the encoded form working on the foreign site's own hostname |

The last test of the file asserts that the encoded form still works on the foreign site's **own** hostname. A filter that blocked everywhere would satisfy every other assertion.

Forms that cannot be observed over HTTP, since a block is now a plain 404, are covered by the JUnit tests.

## Unit tests

Two classes in `src/test/java/org/jahia/community/modules`:

- `CrossSitesFilterTest`: the decision matrix with mocked request, response and chain; dispatcher types; log-line sanitisation; failure handling for the site list and the hostname lookup (WARN once, INFO on recovery).
- `CrossSitesFilterPathTest`: the site-key resolver, hostname normalisation and the authoring-path pattern.

```bash
(cd .. && mvn clean install)
```

They run without Docker. The suite has 84 cases.

## Reports

- `yarn report:merge`: merge the per-spec mochawesome JSON into `results/reports/report.json`.
- `yarn report:html`: render the HTML report into `results/reports/`.

Videos are kept only for failing specs.
