# AGENTS.md

Guidance for an AI agent or a maintainer about to change `prevent-cross-sites-access`. Read `README.md` (what the module does, for administrators) and `tests/README.md` (the end-to-end suite) first.

## Layout

- `src/main/java/org/jahia/community/modules/CrossSitesFilter.java`: layer 1, the servlet filter (order 0.5, REQUEST and FORWARD). It reads the URL and the parameters as strings, and it is the only layer for `/files`, `/repository`, actions and search hits. Its Javadoc is its decision contract.
- `src/main/java/org/jahia/community/modules/CrossSitesRenderFilter.java`: layer 2, a `RenderFilter` (priority -10, live mode, main resource only). It reads what Jahia resolved (the main resource's site and the render-context site), not strings.
- `SiteDirectory.java` (hostname-to-site lookup, site list, `HostSite`), `SitePaths.java` (layer 1's path reading), `SiteIdentifiers.java` (`jsite` identifiers), `FailureTracker.java` (rate-limited WARN, shared), `LogValues.java` (sanitise and cap, shared). Both layers use the shared classes, so they apply the same normalisation, stand-downs and logging.
- `src/main/java/org/jahia/community/modules/SearchParameterCheck.java`: package-private, no Jahia services. The `src_*` rules: which parameters make a potential search (a non-blank value on a `SearchCriteria` property, read from the class at runtime), how unknown and blank values are ignored, and which scope such a search reaches. It was split out of the filter to keep that class small; the filter calls it.
- `src/test/java/org/jahia/community/modules/`: `CrossSitesFilterTest` (decision matrix, failure handling, logging, the `/sites` root check), `CrossSitesFilterPathTest` (site-key resolver, normalisation, authoring pattern), `CrossSitesFilterParameterTest` (`src_*` search parameters and `jsite`, through `SearchParameterCheck`).
- `CrossSitesRenderFilterTest.java` (in the same directory) covers layer 2.
- `src/main/import/`, `src/main/resources/`: Jahia packaging resources (import, CND, properties). The filter does not read any of them.
- `pom.xml`: parent `jahia-modules` 8.2.3.2, packaging `bundle`, Surefire 3.6.0 with `failIfNoTests`, JUnit 5, Mockito 5, AssertJ. `jahia.modules.bundle.removeheaders` removes `Jahia-Source-Folders`, so the released manifest does not carry the builder's checkout path. Keep that property when editing the parent or the bundle plugin. The test-scoped Guava 33.0.0-jre dependency exists because Jahia's render classes need Guava to be mocked; keep its version aligned with the Jahia line (8.2.3.2 ships 33.0.0-jre).
- `tests/`: the end-to-end harness. `cypress/e2e/01-crossSitesAccess.cy.ts` and `02-crossSitesBypasses.cy.ts` are the specs; `cypress/support/crossSites.ts` holds the fixtures and assertion helpers; `cypress/plugins/index.js` defines the `rawRequest` task. Shell entry points: `ci.build.sh`, `ci.startup.sh`, `set-env.sh`, `env.run.sh`, `env.debug.sh`, `ci.postrun.sh`. Docker: `docker-compose.yml`, `Dockerfile`, `.dockerignore`. `package.json` with `yarn.lock` (Yarn 1). `tests/artifacts/` and `tests/results/` are generated.
- Never open or print `tests/.env` or `tests/.env.example`. They hold secrets and are not needed to change the code.

## Commands

- Build and unit tests: `mvn clean install` at the repository root. JDK 11 or later. No Docker.
- SonarQube: `mvn clean install sonar:sonar`. It works on Java 11 as it is, because the parent brings `sonar-maven-plugin` 5.6.0, which provisions its own Java runtime. The project key is `org.jahia.community.modules:prevent-cross-sites-access` (groupId and artifactId from `pom.xml`). Check the analysis is fresh before reading results (see the traps below).
- End-to-end on the default image: `cd tests && bash ci.build.sh && bash ci.startup.sh`.
- End-to-end on the reference image: `cd tests && JAHIA_IMAGE=ghcr.io/jahia/jahia-ee-dev:8.2.3.2 bash ci.startup.sh`.
- After any Java change, run `mvn clean install` again before `ci.build.sh`. After any change under `tests/`, run `ci.build.sh` again.
- Lint: run it inside the test image, never with a host `yarn install`. The image holds `tests/` as of the last `ci.build.sh`, so run that first after editing. Verified:
  `docker run --rm --entrypoint /bin/bash jahia/prevent-cross-sites-access:latest -c 'cd /home/jahians && yarn lint && npx prettier --check "cypress/**/*.{ts,js}" cypress.config.ts'`

## The decision contract

The authoritative text is the Javadoc of `CrossSitesFilter` and the README sections "What it protects against", "Request parameters" and "What it does NOT cover". Do not copy them here; keep them in step with the code. In short: the filter runs on REQUEST and FORWARD at order 0.5. It skips error dispatches and authoring paths. It blocks a live request when the path, or a site-selecting parameter (`jsite`, `src_*`), reaches a site other than the hostname's site and `systemsite`. A block sets the `siteKey` attribute and sends 404. Anything else goes on to the chain.

## Rules when changing the filter

1. **Mirror the renderer, for layer 1.** Any change to how a path, parameter or site key is parsed must be checked against the Jahia source it mirrors (the Javadoc names the classes and the version, jahia-impl 8.2.2.1), and against a live stack on 8.2.3.2 and on the default image. Layer 2 reads no string, so this rule does not apply to it. Record the measured bypass in the commit message.
2. **Every block does the same things, in this order, on either layer:** one INFO line (sanitised), `setAttribute("siteKey", <the hostname's site>)`, then the refusal. On layer 1 the refusal is `sendError(404)` followed by `return`, without calling the chain. On layer 2 it is `throw new PathNotFoundException`, which Jahia turns into a 404. Do not add a third exit path.
3. **Fail open, rate-limited.** A failing lookup (site list, hostname, site identifier) must not throw into the request. It lets the request through, logs one WARN, then at most one WARN every 10 minutes, and one INFO on recovery. An empty site list counts as a failure. Use `FailureTracker`; do not write a new one.
4. **Sanitise and cap everything logged.** Host, path, referer, parameter names and values go through the shared `LogValues` (`sanitise`: control, format and line-separator characters become `?`; `abbreviate`: 512 characters, never splitting a surrogate pair; `safe` does both). Log with SLF4J placeholders and sanitised arguments.
5. **Read only what the renderer reads.** Parameters come from `getParameterMap()`. Never read the request body. Do not read or write the request attribute that `ServerNameToSiteMapper` caches.
6. **Keep the registration.** Order 0.5, REQUEST and FORWARD dispatches. The orders of the surrounding filters are defined in jahia-impl's `applicationcontext-basejahiaconfig.xml`; re-check them when moving to a new Jahia line.
7. **Java 11 and Jahia 8.2.3.2.** The bundle declares `Jahia-Required-Version: 8.2.3.2` and targets Java 11. Do not use newer APIs.
8. **Judge the scope of every potential search. Never mirror Jahia's emptiness rule** (`SearchCriteria#isEmpty`): iteration 2 did, missed node-property criteria, and reopened the channel. Criteria names come from `SearchCriteria` at runtime; unknown and blank values are ignored, as BeanUtils and the provider ignore them. The rules live in `SearchParameterCheck`.
9. **Keep the existing semantics.** `systemsite` is always allowed. An unknown key is not a cross-site target. An unmapped hostname, `localhost` or an empty hostname means no check (documented fail-open).
10. **Keep both layers.** A change must keep both. Never move a check from layer 1 to layer 2 for a surface that never enters the render chain (`/files`, `/repository`, `*.do` actions, search hits printed by a JSP): layer 2 cannot see those. A check on a rendered page may go to layer 2 when it judges what Jahia resolved, and layer 1 keeps its string check for the surfaces above.

## Testing rules

- **Unit tests for what HTTP cannot observe.** A block and an unknown key both answer 404, so the decision matrix, key resolution, normalisation, parameter parsing, failure tracking and logging are tested in JUnit.
- **E2E assertions compare the unguarded answer with the withheld one**, through `expectBlocked`, `expectSelectionBlocked` or `expectWithheldWhereServed`. A status alone proves nothing, because a block and a missing page look the same.
- **Every e2e assertion must be able to fail.** Missing probes, empty markers and unreadable mappings must fail the run, not pass it vacuously. The helpers already do this; keep it that way.
- **Run the e2e suite on 8.2.3.2 as well as the default image.** Core masks module defects on newer versions: its `MalformedRequestFilter` (present in 8.2.3.7 and in the 8-SNAPSHOT image) refuses some forms before the module runs. A green run on the newer image says less.
- **Do not weaken a helper to make a test pass.** If a test fails, first decide whether the code or the premise of the assertion is wrong.
- **A new bypass test must name the layer that must refuse it.** A test that passes only because the other layer refuses proves nothing about the layer it claims to cover.
- **Docs:** do not hard-code the JUnit count. The e2e tables in `tests/README.md` must match the spec files: grep each title.

## Known traps (measured)

- On Jahia 8.2, `/cms/edit/...` redirects every visitor, guest included, to the page composer. Use `/cms/editframe/default/...` for the edit frame, and `/cms/render/default/...` for the session probe.
- Jackrabbit reads one leading `{}` per path segment as no marker: `{}name` addresses `name`. `{}{}x` is not normalised further, and that must stay so (`normalizeJcrPath`).
- `createSite()` publishes only the bare site node. The site's pages are not live until they are published. `ensureForeignSite()` judges publication by the home page.
- SonarQube results read right after an upload can be those of the previous analysis. Before reading issues, confirm the analysis is new with a metric that must have changed, such as the analysed line count or the analysis date.
- Several agents may share one working tree. Commit with `git commit -- <paths>`, or stage exactly your own paths first, so another agent's staged changes are not committed with yours.
- `ci.build.sh` refuses a jar older than any file under `../src` or `../pom.xml`. A stale jar is the usual cause of a failed run after an edit.
- `tests/.yarnrc.yml` is a Yarn Berry file, but `packageManager` pins Yarn 1.22.22 and the test image runs Yarn 1. A host `yarn install` can rewrite the lockfile.
- `set-env.sh` sources `tests/.env` into the current shell. Run `source set-env.sh` again in each new terminal.
- `./ci.startup.sh notests` boots Jahia WITHOUT provisioning. Provisioning runs in the Cypress container, so a `notests` stack has no `digitall` and no test site. To inspect a provisioned platform, run the full suite: `ci.startup.sh` leaves the `jahia` container running afterwards (only `cypress` exits), with both test sites provisioned, so it can be probed on `localhost:8080`. Stop it with `docker compose -f tests/docker-compose.yml down`.
- The `/sites` node (`/sites.html.ajax`, `/sites/.html.ajax`) names no site key. On 8.2.3.2 its view lists every site; on 8-SNAPSHOT it renders an empty body. The root-listing e2e test is therefore conditional on what the unguarded host reveals (`expectServedUnguardedThenBlocked`). A green run on 8-SNAPSHOT proves less for that test.
- A top-level `/<one-segment>/sites.<ext>` looks like the SEO form and is blocked (known over-block).

- The render layer refuses through `PathNotFoundException`. Its logs and its 404 page go through the same ErrorServlet path as layer 1, so the block line is the only way to tell the two apart. Its block line carries `(render layer)`; layer 1's does not.
- Layer 2 checks the main resource only, on purpose. Content references and systemsite content included in a page are not checked.
- `/files`, `/repository`, actions and JSP-printed search hits never reach the render chain: only layer 1 covers them.
- `tests/` is 36 tests (19 in `01`, 17 in `02`). The suite has no test that disables a layer. The layer-by-layer separation was measured outside the suite on 8.2.3.2.

## Decision: two layers, and what each one owns

Two layers, in one bundle, each with its own job:

- **Layer 1, `CrossSitesFilter`** (servlet filter, order 0.5): owns everything that never enters the render chain. That is `/files`, `/repository`, actions (`*.do`, POST, run in `Render` before the render chain), and search hits (printed by the search results JSP). It reads the URL and the parameters as strings.
- **Layer 2, `CrossSitesRenderFilter`** (`RenderFilter`, priority -10, live mode, main resource only): owns rendered pages. It reads what Jahia resolved: the main resource's site and the render-context site. It refuses the repository root and the `/sites` node.

Neither is enough alone. A foreign path shows in the node's site, but `jsite` shows only in the render-context site. Measured on 8.2.3.2: layer 2 alone refuses every rendered bypass, but a foreign `/files` PDF and a foreign search are stopped only by layer 1.

The rule that follows: a change keeps both layers. A check moves to layer 2 only for a rendered surface, and never away from layer 1 for a surface that bypasses the render chain. Sub-resources and content references are not checked by layer 2, on purpose.

History. The servlet layer was the whole filter until the render layer was added, and each review round found a channel the string reading had missed:

- the raw request target instead of the decoded path (`%73ites`);
- a FORWARD pass that decodes a second time (`%2564`);
- the JCR expanded-name form (`{}name`);
- the renderer's handling of a trailing `/` and `/*`;
- request parameters read by `Render` (`jsite`) and by the search module (`src_*`);
- the `/sites` node itself, which names no site key (`/sites.html.ajax`);
- node-property search criteria (`src_properties(...)`), which a mirror of Jahia's emptiness rule did not list. That mirror was removed, and the scope of every potential search is judged instead.

The render layer was added because a string reading is always one spelling behind. It judges what Jahia resolved, so a new spelling does not change its answer for rendered pages. Layer 1 keeps its strings for the surfaces that the render chain never sees.

## Before you hand a change back

- `mvn clean install` passes at the repository root.
- If the filter changed: the unit tests cover the new decision, and the e2e suite ran on 8.2.3.2 and on the default image.
- If behaviour, log lines, requirements or the test list changed, the matching text is updated in `README.md` (administrators), `tests/README.md` (the suite, with exact titles) and this file.
- No file under `tests/` that holds secrets is staged, and the commit lists its paths explicitly.
