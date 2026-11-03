# Tests

This directory holds the end-to-end suite for `prevent-cross-sites-access`, built on the standard `@jahia/cypress` Docker harness. Unit tests live in `src/test/java` and run from the repository root with `mvn clean install` (no Docker needed).

The module is a servlet filter that answers 404 when the site a request path addresses differs from the site its hostname maps to. Every spec is built around controlling those two inputs independently.

## Prerequisites

- **Docker** with the Compose plugin (`docker compose`).
- **A Jahia license**, supplied as `JAHIA_LICENSE` in `tests/.env`. That file is git-ignored and excluded from the test image by `.dockerignore`; the license is never baked into an image.
- **Maven and a JDK**, to build the module jar.
- **Node.js and Yarn 1** (the package manager is pinned to `yarn@1.22.22`), only for local runs against a node. The Docker image installs its own dependencies.
- **Registry access** to the Jahia image you choose.

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
| `JAHIA_PORT` | `docker-compose.yml`, `cypress/support/crossSites.ts` | TCP port of the specs; defaults to `8080` when empty or unset |
| `NEXUS_USERNAME`, `NEXUS_PASSWORD` | `docker-compose.yml` (`cypress` service) | Passed through to the Cypress container. What the harness does with them is not documented in this directory |

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
- the default `8-SNAPSHOT` image (an 8.2.4.0-SNAPSHOT build)

Use **8.2.3.2 as the reference target**. Jahia core's own `MalformedRequestFilter` is absent there, so double-encoded and non-normalised URLs reach the module, and a green run shows that the module itself refuses them. On 8.2.3.7 and on the 8-SNAPSHOT image that core filter is present and rejects such URLs with 400 before the module runs. The specs still pass there, but for those forms they no longer prove anything about the module. Their assertions are written as properties ("foreign content is not served"), so they hold whichever layer refuses.

On 8.2.3.2, dot-segment requests are also refused with 400 by a layer before the module. That layer has not been identified, and it is not attributed to `MalformedRequestFilter`.

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

`set-env.sh` exports the variables from `tests/.env` into the current shell only, so every new terminal needs `source set-env.sh` again. If you skip it, `yarn run e2e:debug` starts without those variables. The specs then use their own defaults for `JAHIA_HOST` (`jahia`) and `JAHIA_PORT` (`8080`), but any other variable the harness needs is missing. The exact error this produces was not reproduced for this README.

`JAHIA_HOST` (default `jahia`) is only the TCP target. On a local node either add `127.0.0.1 jahia` to `/etc/hosts`, or set `JAHIA_HOST=localhost` in `tests/.env`. The specs send their own `Host` header, so a `localhost` connection target does not weaken them.

`yarn lint` runs ESLint over the TypeScript specs.

## Reading the filter's log

The filter logs under `org.jahia.community.modules.CrossSitesFilter`. With the compose stack running, the Jahia container is named `jahia`:

```bash
docker logs jahia 2>&1 | grep CrossSitesFilter
```

Blocked requests appear as `Blocked cross-site access: ...` at INFO. The DEBUG line for hostnames that map to no site only appears when that logger is raised to DEBUG in Jahia's logging configuration; this suite does not set it. See the root README, "Diagnosing a request the module blocked", for the full list of messages.

## How the specs drive the filter

Both specs map a hostname to `digitall` with `editSite`, then send requests through the `rawRequest` Cypress task (`cypress/plugins/index.js`) rather than `cy.request`. Two reasons:

- **The request-target is sent byte for byte.** `cy.request` resolves `..` segments and re-encodes percent escapes before anything reaches the socket, which is exactly the transformation the filter is tested against. Normalising the payload would make the bypass specs assert nothing while still passing.
- **`Host` is set independently of the connection target.** The specs reach every branch of the hostname lookup (mapped site, unmapped host, `localhost`) with no DNS or compose aliases. The fixture hostnames `prevent-cross-sites.test`, `cross-sites-other.test` and `not-a-jahia-site.test` are only ever sent as `Host` headers.

### Two real sites: served versus withheld

Both specs run against two real sites:

- `digitall`, mapped to `prevent-cross-sites.test` for the duration of a spec;
- `crosssitesother`, the foreign site, served on `cross-sites-other.test`.

`ensureForeignSite()` in `cypress/support/crossSites.ts`:

- creates the foreign site only if it is missing;
- publishes it only when its home page is not already served;
- sets its hostname on every run;
- uploads and publishes the probe file `crossSitesProbe.txt` into the foreign site's `files` folder, and a second probe, `crossSitesSharedProbe.txt`, into `/sites/systemsite/files`. A probe is replaced only when `/files/live` does not already serve it with the fixture's content. **The shared probe is written into `systemsite`, which every site uses, and it is not removed afterwards.**

The foreign site is deliberately not deleted; the CI container is discarded either way.

The markers used to detect foreign content are derived at runtime from the foreign home page (its `<title>` and one `/sites/crosssitesother/...` asset reference) and filtered against an ordinary 404 page. The "no `/sites/` segment" test uses a `/modules/...css|js` asset that the foreign page references, also found at runtime. Nothing is hard-coded.

A made-up site key would prove nothing: Jahia resolves an unknown key to no site and answers 404 on its own, so "404 instead of content" would hold with the module uninstalled. With a real foreign site the comparison is **200 (served on an unguarded host) against 404 (withheld on the mapped host)**. `expectBlocked()` asserts three things: the same URL is 200 on the unmapped host, it is 404 on the mapped host, and the body contains no foreign marker and differs from the unguarded body.

Where the platform itself refuses a URL form, `expectWithheldWhereServed()` is used instead: if the unguarded host serves the foreign content, the mapped host must answer 404 without it; if the platform refuses the URL, the suite logs that and asserts only that no foreign content is served.

Authentication is proven, not assumed. `sessionCookieHeader()` logs in and checks that a default-workspace page answers 200 with the session cookie and not without it. The `/cms/edit/...` URL is not used for this, because on 8.2 it redirects every visitor, guest included.

Both specs restore `digitall`'s original `j:serverName` in `after()`, and both prove the session state they depend on before asserting. If `digitall` already has the test hostname when a run starts, which means an earlier run was aborted before its `after()`, `readSiteServerName()` stops with an error. Reset `j:serverName` by hand and rerun.

## The two specs

Test titles below are the exact `it()` titles in the spec files, so a failing test can be looked up here by name. Titles built from a loop are written out for each value.

### `01-crossSitesAccess.cy.ts`: `CrossSitesFilter - enforcement`

| Group | Exact title | What it asserts |
|---|---|---|
| Calibration | `serves the second site's content on its own hostname` | 200 with the foreign page's markers: there is content to withhold |
| Enforcement | `blocks another site's live content requested over the mapped hostname` | 200 on an unguarded host, 404 on the mapped one, no foreign marker, body differs from the unguarded body |
| | `blocks another site's live files requested over the mapped hostname` | the same, with the probe file's content as the marker, on `/files/live` |
| | `blocks a trailing-dot hostname exactly like the plain mapped hostname` | `prevent-cross-sites.test.` is blocked like the plain name |
| | `blocks an upper-case hostname exactly like the plain mapped hostname` | Host case makes no difference |
| Own site | `serves the mapped site's own content exactly as it would with no filter in play` | `digitall`'s live home page is 200 on the plain, trailing-dot and upper-case hosts, and the status matches the unguarded host |
| Static assets | `ignores request paths that carry no /sites/ segment` | a `/modules/...` asset is 200 on the mapped host and on the unguarded host |
| Fail-open | `does not block anything when the hostname maps to no site` | foreign content served in full on an unmapped host |
| | `does not block anything when the hostname is localhost` | foreign content served in full on `localhost` |
| Authoring | `does not enforce authoring URLs (default workspace files) for another site` | the default-workspace file of the foreign site answers as it does on the foreign host, and not 404 |
| Dot segments | `cannot be bypassed with dot segments (tripwire on the layer before the module)` | on the mapped host, foreign content is not served; on the unmapped host the answer is exactly 400, from a layer before the module |

Under `guests and authoring URLs` (a describe block), with no session:

| Exact title | What it asserts |
|---|---|
| `gets on the mapped hostname exactly what it gets on the foreign host for the default-workspace render URL` | same status as on the foreign host, no foreign content in the body |
| `gets on the mapped hostname exactly what it gets on the foreign host for the edit frame URL` | the same |
| `gets on the mapped hostname exactly what it gets on the foreign host for the default-workspace file URL` | the same |

Under `authenticated editors` (a describe block):

| Exact title | What it asserts |
|---|---|
| `can still open the edit frame for the site on its own hostname` | 200 for an authenticated session on the site's own host |
| `are blocked from another site live content just like anonymous visitors` | the live render of the foreign site is blocked for an authenticated session, as for a guest |
| `are not blocked on another site's edit frame URL over the mapped hostname` | same status as on the foreign host, not 404 |
| `are not blocked on another site's edit URL over the mapped hostname` | the same |
| `are not blocked on another site's default-workspace render URL over the mapped hostname` | the same |

The fail-open tests are asserted rather than hidden: the control is hostname-driven, so an unmapped host or `localhost` is served in full.

The dot-segment test is a tripwire on the layer in front of the module. The unmapped-host request is refused with 400 where this module provably does nothing, so that refusal comes from a layer before the module. The mapped-host assertion is "foreign content not served", which holds whichever layer refuses. If the platform ever stops screening traversal, both requests return 200 and the test reports that the module is newly exposed.

Unknown site keys and forms where the site key is the last path segment are not asserted over HTTP: they are 404 whether or not the filter acts. The JUnit tests cover them.

### `02-crossSitesBypasses.cy.ts`: `CrossSitesFilter - former bypasses and false positives`

These are regression tests for defects that are fixed, plus one control. Both specs are expected to pass.

| Defect | Effect before the fix | Exact title that pins it |
|---|---|---|
| The filter matched the raw request URI, not the decoded path the renderer uses | `/cms/render/live/en/%73ites/<foreign>/home.html` (`%73` is `s`) returned the foreign site's page | `blocks a foreign site reached through a percent-encoded /sites/ segment` |
| The filter ran on REQUEST only; Jahia's SEO inbound rule forwards, and the forward decodes a second time | measured on 8.2.3.2: `/en/sites/%2564igitall/home.html` over another site's hostname returned digitall's full home page | `does not serve a foreign site addressed with a double-encoded key in the SEO short form` |
| The JCR expanded-name form `{}name` (empty namespace) addresses the same node as `name`, and the filter did not read it as a site key | measured on released 8.2.3.7: `/cms/render/live/en/sites/%7B%7D<other>/home.html` served the other site | `blocks a foreign site named with the expanded-name form in the site key`; also `blocks a foreign site named with the expanded-name form in the /sites/ segment`, `blocks a foreign site named with the expanded-name form in the SEO short form`, and `blocks a foreign live file named with the expanded-name form in the site key` |
| `systemsite` was treated as a foreign site | shared content under `systemsite` failed on every site using it | `still serves cross-site shared content stored under systemsite` |
| (control) | a filter that blocked everywhere would satisfy every test above | `leaves the encoded form working on the foreign site's own hostname` |

The double-encoding test uses `expectWithheldWhereServed()`, so it is a positive control: where the platform serves the URL on an unguarded host, the mapped host must withhold it.

The `systemsite` test checks a real probe file in `/sites/systemsite/files` by its content, not only its status.

Forms that cannot be observed over HTTP, since a block is a plain 404, are covered by the JUnit tests.

## Unit tests

Two classes in `src/test/java/org/jahia/community/modules`:

- `CrossSitesFilterTest`: the decision matrix with mocked request, response and chain; dispatcher types; log-line sanitisation; failure handling for the site list and the hostname lookup, including the WARN and the INFO on recovery.
- `CrossSitesFilterPathTest`: the site-key resolver, hostname normalisation, the JCR `{}` normalisation and the authoring-path pattern.

```bash
(cd .. && mvn clean install)
```

They run without Docker.

## Reports

- `yarn report:merge`: merge the per-spec mochawesome JSON into `results/reports/report.json`.
- `yarn report:html`: render the HTML report into `results/reports/`.

Videos are kept only for failing specs.
