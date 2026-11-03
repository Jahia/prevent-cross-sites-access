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

Both specs run against **two real sites**: `digitall`, and a second site the fixture creates if
it is missing (`ensureForeignSite()` in `cypress/support/crossSites.ts`). A made-up site key
would prove nothing — Jahia resolves an unknown key to no site, falls back to the hostname's
site and answers 404 on its own, so "404 instead of the content" would hold with the module
uninstalled. With a real site the comparison is **200 (served) against 400 (refused)**, which is
the disclosure the module exists to prevent.

Site creation is skipped when the second site already serves its home page, so repeated local
runs pay for it once. It is deliberately not deleted afterwards; the CI container is discarded
either way.

Each spec restores `digitall`'s original `j:serverName` in `after()`.

## The two specs

| Spec | Covers |
|---|---|
| `01-crossSitesAccess.cy.ts` | the behaviour the module is meant to have, including the documented fail-open branches |
| `02-crossSitesBypasses.cy.ts` | the URL forms that used to walk past the filter, kept as regression tests |

Both are expected to pass. `02` was the TDD red state for three defects, which differ in
severity:

| Defect | Severity | Measured |
|---|---|---|
| matched the raw `getRequestURI()`, not the decoded path | **content disclosure** | `/%73ites/<other>/home.html` returns 200 with the other site's home page |
| `substringBetween` returned null when the site key ends the path | filter bypassed, nothing exposed | `/sites/<key>` and `/sites/<key>.html` are 404 even on the site's own hostname |
| `systemsite` read as a foreign site | availability | shared media 400'd on every site referencing it |

Each test in `02` now pins one of those fixes. Its last test asserts the same URL forms still
work on the site's **own** hostname, because a filter that answered 400 unconditionally would
satisfy every other assertion in the file.

### One defect that is real in the code but not reachable

The same parser gap suggests a dot-segment bypass
(`/sites/digitall/../<other>/home.html`). Measured against 8.2 it is **not exploitable**: Jahia
core's `org.jahia.bin.filters.MalformedRequestFilter` answers 400 for a non-normalised URI and
for the `%2e%2e` form, before `CrossSitesFilter` runs. That assertion therefore lives in `01` —
paired with the same request over an *unmapped* hostname, where the filter provably cannot act.
Both must return 400; if core ever stops screening traversal, both turn 200 and the test reports
that the module is newly exposed.

## What the filter does

It flags a request when the site the path addresses is **a different existing site** from the one
the hostname maps to. Three things follow, and all three are asserted:

- the path is read **decoded** (`getServletPath() + getPathInfo()`), the same string the renderer
  resolves content from — so `/%73ites/` cannot mean one thing here and another there;
- the site key is extracted the way `URLResolver.resolveSiteKeyFromPath` extracts it, including
  the trailing-segment and template-extension forms, and the dot ambiguity is settled against the
  real site list rather than by cutting at the first `.`;
- only an **existing** site counts as a cross-site target. An unknown key is left to Jahia's own
  404, so unrelated paths that merely contain `/sites/` — a module's static resources, say — are
  not turned into 400s. `systemsite` is exempt for the same reason: it is shared by every site.

The control is hostname-driven, so it stands down entirely on a hostname that maps to no site or
on `localhost`. That is inherited from `ServerNameToSiteMapper`; `01` asserts it rather than
hiding it.

## Reports

- `yarn report:merge` — merge per-spec mochawesome JSON into `results/reports/report.json`
- `yarn report:html` — render the HTML report into `results/reports/`

Videos are kept only for failing specs.
