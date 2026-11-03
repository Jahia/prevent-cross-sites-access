# AGENTS.md

Guidance for an AI agent or a maintainer about to change `prevent-cross-sites-access`. Read `README.md` (what the module does, for administrators) and `tests/README.md` (the end-to-end suite) first.

## Layout

- `src/main/java/org/jahia/community/modules/CrossSitesFilter.java`: the whole module, one servlet filter registered as an OSGi component. Its Javadoc is the decision contract.
- `src/test/java/org/jahia/community/modules/`: `CrossSitesFilterTest` (decision matrix, failure handling, logging), `CrossSitesFilterPathTest` (site-key resolver, normalisation, authoring pattern), `CrossSitesFilterParameterTest` (`src_*` search parameters and `jsite`).
- `src/main/import/`, `src/main/resources/`: Jahia packaging resources (import, CND, properties). The filter does not read any of them.
- `pom.xml`: parent `jahia-modules` 8.2.3.2, packaging `bundle`, Surefire 3.6.0 with `failIfNoTests`, JUnit 5, Mockito 5, AssertJ.
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

1. **Mirror the renderer.** Any change to how a path, parameter or site key is parsed must be checked against the Jahia source it mirrors (the Javadoc names the classes and the version, jahia-impl 8.2.2.1), and against a live stack on 8.2.3.2 and on the default image. Record the measured bypass in the commit message.
2. **Every block does the same three things, in this order:** one INFO line (sanitised), `setAttribute("siteKey", <the hostname's site>)`, then `sendError(404)` followed by `return`, without calling the chain. Do not add a second exit path.
3. **Fail open, rate-limited.** A failing lookup (site list, hostname, site identifier) must not throw into the request. It lets the request through, logs one WARN, then at most one WARN every 10 minutes, and one INFO on recovery. An empty site list counts as a failure. Use `FailureTracker`; do not write a new one.
4. **Sanitise and cap everything logged.** Host, path, referer, parameter names and values go through `sanitise` (control, format and line-separator characters become `?`) and `abbreviate` (512 characters, never splitting a surrogate pair). Log with SLF4J placeholders and sanitised arguments.
5. **Read only what the renderer reads.** Parameters come from `getParameterMap()`. Never read the request body. Do not read or write the request attribute that `ServerNameToSiteMapper` caches.
6. **Keep the registration.** Order 0.5, REQUEST and FORWARD dispatches. The orders of the surrounding filters are defined in jahia-impl's `applicationcontext-basejahiaconfig.xml`; re-check them when moving to a new Jahia line.
7. **Java 11 and Jahia 8.2.3.2.** The bundle declares `Jahia-Required-Version: 8.2.3.2` and targets Java 11. Do not use newer APIs.
8. **Keep the existing semantics.** `systemsite` is always allowed. An unknown key is not a cross-site target. An unmapped hostname, `localhost` or an empty hostname means no check (documented fail-open).

## Testing rules

- **Unit tests for what HTTP cannot observe.** A block and an unknown key both answer 404, so the decision matrix, key resolution, normalisation, parameter parsing, failure tracking and logging are tested in JUnit.
- **E2E assertions compare the unguarded answer with the withheld one**, through `expectBlocked`, `expectSelectionBlocked` or `expectWithheldWhereServed`. A status alone proves nothing, because a block and a missing page look the same.
- **Every e2e assertion must be able to fail.** Missing probes, empty markers and unreadable mappings must fail the run, not pass it vacuously. The helpers already do this; keep it that way.
- **Run the e2e suite on 8.2.3.2 as well as the default image.** Core masks module defects on newer versions: its `MalformedRequestFilter` (present in 8.2.3.7 and in the 8-SNAPSHOT image) refuses some forms before the module runs. A green run on the newer image says less.
- **Do not weaken a helper to make a test pass.** If a test fails, first decide whether the code or the premise of the assertion is wrong.
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

## Open design question

The filter mirrors Jahia's readers as string checks: the path (`resolveSiteKeyFromPath` and its helpers), the search parameters (`SearchCriteriaFactory` and `JahiaJCRSearchProvider`) and `jsite` (read by `Render`). Each round so far found a channel where the string the filter read was not what Jahia used:

- the raw request target instead of the decoded path (`%73ites`);
- a FORWARD pass that decodes a second time (`%2564`);
- the JCR expanded-name form (`{}name`);
- the renderer's handling of a trailing `/` and `/*`;
- request parameters read by `Render` and by the search module (`jsite`, `src_*`).

Every fix mirrors one more reader. A new reader, or a parser change in Jahia, reopens the class. The alternative is a render-chain check: compare the site of the node the renderer actually resolved with the hostname's site. That check would have to sit later in the chain, closer to the renderer, and it would not have been checked against any of the bypasses above. This is not implemented, and its trade-offs are not measured. Settle it before adding a fourth reader.

## Before you hand a change back

- `mvn clean install` passes at the repository root.
- If the filter changed: the unit tests cover the new decision, and the e2e suite ran on 8.2.3.2 and on the default image.
- If behaviour, log lines, requirements or the test list changed, the matching text is updated in `README.md` (administrators), `tests/README.md` (the suite, with exact titles) and this file.
- No file under `tests/` that holds secrets is staged, and the commit lists its paths explicitly.
