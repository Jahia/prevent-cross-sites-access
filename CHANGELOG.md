# Changelog

## [2.0.0] - Unreleased

A major version: this release raises the minimum Jahia version (8.1.0.0 to 8.2.3.2) and the Java requirement (8 to 11), and changes the response to a blocked request (400 to 404).

### Breaking changes / upgrade notes

For administrators:

- **Minimum Jahia is 8.2.3.2** (was 8.1.0.0). The bundle declares `Jahia-Required-Version: 8.2.3.2` and does not install on earlier releases.
- **Minimum Java is 11** (was 8). The bundle manifest requires JavaSE 11. The build needs JDK 11 or later.
- **A blocked request gets HTTP 404 with the error page of the visitor's own site**, the site their hostname maps to. Before, it got an empty 400 with no document. Alerts or dashboards that match 400 from this module will stop matching. A block is now visible only in the log.
- **Only live content is enforced.** Authoring and default-workspace URLs (`/cms/*/default`, `/files/default`, `/files/{workspace}`, `/repository/default`) are no longer blocked; Jahia login and ACLs govern them. An editor can open another site's authoring views from a back-office hostname mapped to a site. A live view of another site stays blocked for every visitor, editors included.
- **`systemsite` is no longer blocked.** It holds content shared by all sites. Before, shared content failed on every site that referenced it.
- **The urlrewrite rule file `last-urlrewrite-prevent-cross-sites-access.xml` is removed.** The filter is the only enforcement point, so it no longer depends on a rewrite rule loading.
- **Site-selecting request parameters are now checked:** `jsite` and the search parameters with the `src_` prefix. A request carrying a real search-criteria parameter whose scope reaches beyond the hostname's site (another site, every site, or the whole repository) now returns 404 when made from a mapped hostname. A request with no scope that carries only a search parameter such as the language also returns 404. Unrelated `src_*` parameters and blank-only values do not cause a 404.
- **Flush the HTML cache once after installing.** Before this release a page's ajax fragment could be cached with another site's content in it (see Security). Flushing removes any such fragment.

### Security

Each item below is a bypass of this module's protection. Plain Jahia serves every site over every hostname by default.

- **Raw URI matched instead of the decoded path.** The site named in the path was read from the undecoded request URI, while the renderer resolves the decoded path. A percent-encoded `sites` segment was not recognised, and another site's live home page was served on a mapped hostname. Matching now uses the decoded path, as Jahia does.
- **Site key as the final path segment.** The site-key extraction returned nothing when the key ended the path, so those requests were never checked. Such URLs render no content (Jahia answers 404 even on the owning hostname), so nothing was exposed. Closed to match the renderer.
- **Double encoding through the SEO forward (Jahia 8.2.3.2 and earlier).** The SEO inbound rule forwards the request, and the forward decodes the path a second time. A double-encoded site key on a mapped hostname served the other site's full home page. The filter now also runs on FORWARD dispatches. Jahia 8.2.3.7 and later refuse double-encoded paths in core, but the module no longer depends on that.
- **Trailing-dot hostnames.** A hostname with a trailing dot names the same host but mapped to no site, so the filter stood down. Trailing dots are now removed before the lookup.
- **JCR expanded-name form `{}name`.** JCR treats an empty-namespace marker on a path segment as the same node. On Jahia 8.2.3.7 a `{}`-prefixed site key was not recognised, and another site's home page was served. The marker is now stripped from each segment before the site is resolved.
- **Site selection through search parameters.** The `src_` search parameters could select another site's content on the visitor's own search-results page (titles, excerpts, extracted PDF text). Search criteria on node properties were also able to select another site's content. Such requests are refused unless every scope stays within the hostname's site or `systemsite`. Scopes that are refused include all sites, a blank site value, a page or file path outside the site, and no scope at all (the whole repository).
- **Sites folder listing.** A request that addressed the `sites` folder node itself, rather than a site, was not checked. On Jahia 8.2.3.2 that view lists every site's title, server name and top-level nodes, over any hostname. Such requests are now refused on mapped hostnames. Pages that merely contain a `sites` segment under the visitor's own site are unaffected. On Jahia 8-SNAPSHOT the same view renders an empty body.
- **Site selection through `jsite`.** The `jsite` parameter, which swaps the site used to render a page, could render another site's navigation inside the visitor's page. It is refused unless its identifier is the hostname's site or `systemsite`. A blank `jsite` is ignored.
- **Site selection through `jarea`.** On an ajax render of a page, the `jarea` parameter makes Jahia render any node, by identifier, inside that page. Neither layer checked it, so the visitor's own page on its own hostname rendered another site's content. Jahia's HTML cache also ignores the parameter, so the foreign fragment was then served to every visitor of that page. The render layer now refuses a `jarea` naming another site's node, `/sites` or the repository root, on every hostname. Found by the blind scan of 2026-11-04.
- **Search scopes that Jahia does not apply.** The search check counted any parameter whose name started with a scope property as a scope, including spellings Jahia ignores: an unknown sub-property, a mapped form, another letter case, and `.values` on a page or file path. A search scoped only by such a spelling ran over every site from a mapped hostname. Only the spellings Jahia applies now count as a scope. Found by the blind scan of 2026-11-04.

### Added

- **Second, render-chain layer** (`CrossSitesRenderFilter`, live mode, main resource only). It judges the site Jahia actually resolved for the page and the site the page is rendered for, as the renderer computed them, rather than reading the URL as text. It protects rendered content even against URL spellings the servlet layer does not recognise. A block answers 404 with the hostname's site's error page, as the servlet layer does. The servlet layer remains for files, actions, repository URLs and search parameters, which never reach the render chain.
- `AGENTS.md`: guidance for an AI agent or maintainer changing the filter, covering layout, commands, the decision contract, change rules, testing rules, measured traps and the open design question.
- JUnit 5 unit tests: 337 cases in four classes covering site-key extraction, the decision matrix, the authoring-path pattern, hostname normalisation, the search, `jsite` and `jarea` checks, the render layer, and failure handling. They run in `mvn clean install` and need no Docker.
- Cypress end-to-end suite under `tests/`: one spec for intended behaviour (including the documented fail-open on unmapped hostnames) and one for the bypass regressions listed above.
- Docker-based e2e harness: Dockerfile, `docker-compose.yml`, CI scripts (`ci.build.sh`, `ci.startup.sh`, `ci.postrun.sh`), environment handling (`set-env.sh`, `.env.example`) and provisioning manifests.
- Module `README.md`: what the module protects, what a blocked visitor sees, what it does not cover, supported versions, upgrade notes and diagnosis steps.
- `tests/README.md`: harness prerequisites, how to choose the Jahia image, and test tables keyed by test title.
- Activation log: one INFO line on start with the filter order and dispatcher types.
- Block log: one INFO line per blocked request giving the addressed site, the hostname's site, the hostname the server saw, the decoded path and the Referer.
- Diagnostics: a DEBUG line for hostnames that map to no site, and a WARN when the site list or hostname lookup fails. The WARN repeats at most every 10 minutes while the failure lasts, and an INFO line marks recovery.

### Changed

- **Block response:** 400 with an empty body is now 404 with the visitor's own site's error page. The site is set through the `siteKey` request attribute before the error is sent, so the page does not carry the branding or language of the site named in the path.
- **Status of a block matches an unknown site key.** The earlier 400 told a prober which site keys exist. Jahia's own 404 for a missing key can still differ from a block in page content; see the module README.
- **Error pages are not enforced.** Requests that carry an error status are passed through, so a site-level error page under `/errors/sites/<key>/` is not re-blocked into a bare 404. This is a relaxation from 1.0.0.
- **Trailing `/` and trailing `/*` are removed** before the site key is read, as the renderer does.
- **`/files/{workspace}/` is treated as authoring**, like `/files/default/`, so editors no longer get 404 on cross-site images in edit mode.
- **Hostnames with trailing dots** are resolved as the bare hostname.
- **An empty site list is a lookup failure** and is logged, instead of passing silently.
- **Log output:** control, format (bidirectional and zero-width) and line-separator characters are replaced by `?`. Paths and Referer values are capped at 512 characters.
- **Search parameter check follows Jahia's rules.** Any request carrying a real search-criteria parameter has its scope judged, whether or not Jahia would actually run the search. Unrelated `src_*` parameters are ignored, as Jahia's bean population ignores unknown properties, and blank-only values are ignored. A request with no scope that carries only a search parameter such as the language now returns 404. The rules live in a new package-private `SearchParameterCheck` class.
- **Repository root and sites folder node** requests are refused by the render layer too, when the page being rendered is one of those nodes.
- **Released manifest** no longer includes `Jahia-Source-Folders`, which carried the builder's local checkout path. Jahia reads that header only to mount sources in development mode.
- **Filter registration:** REQUEST and FORWARD dispatches, order 0.5. It runs after the Shiro and JCR session filters and before `UrlRewriteFilter`.
- **Build parent** moved from `jahia-modules` 8.1.0.0 to 8.2.3.2. The Sonar scan (`mvn clean install sonar:sonar`) now runs on Java 11 with the current scanner.
- **Surefire is pinned to 3.6.0** with `failIfNoTests`, so a build that finds no tests fails instead of passing.
- **pom:** new description, MIT license declaration and JUnit, Mockito and AssertJ test dependencies.
- **Known limits:** dot-segment paths (`..`) are not parsed by this module. On Jahia 8.2.3.2 they are refused with 400 by a layer that runs before the module; that layer has not been identified and is not Jahia's `MalformedRequestFilter`.
- **Known over-block:** a top-level path of the form `/<one-segment>/sites.<ext>` is treated as the sites root and refused on a mapped hostname, even when that first segment is not a Jahia servlet.

### Fixed

- `ci.build.sh` removes stale SNAPSHOT artifacts, deploys only the module jar, and refuses a jar older than the sources or pom. Previously a stale jar could be tested.
- `ci.startup.sh` passes its arguments through, so `notests` runs no tests. Previously it ran the full suite. It no longer prints the super-user password into CI logs.
- `set-env.sh` returns failure when no environment file exists, and the scripts that source it stop with a message. Previously a no-op run exited 0.
- The test image no longer contains the local `.env` file (licence and passwords).
- Jahia HTTP and debug ports are published on `127.0.0.1` only. The JDWP debug port is opt-in (`JPDA=true`).
- Test assertions can fail: a block is checked against withheld content and a rendered error page, not only against a status code; statuses are exact; the authenticated tests prove the session before relying on it.
- The second test site is published when its home page is not live, instead of timing out.
- The test site's `serverName` fails loudly when it cannot be read and is reset after each run.
- Prettier configuration agrees with the ESLint configuration. `yarn lint` and `prettier --check` both pass.
- Filter code and tests: SonarQube maintainability issues cleared without a behaviour change.
