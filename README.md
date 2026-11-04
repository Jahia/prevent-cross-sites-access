# prevent-cross-sites-access

A Jahia 8 OSGi module that stops a request from reading one site's live content through another site's hostname. It is a single servlet filter, `org.jahia.community.modules.CrossSitesFilter`, and has no configuration.

## What it protects against

Jahia serves every site from one server. A site is normally reached through its hostname (`j:serverName`), but a request can also name any site in the URL path, as in `/sites/<key>/...`, or in a request parameter. Without this module, Jahia renders the site named in the path or the parameter regardless of the hostname the request arrived on.

Example: a reverse proxy exposes `intranet.example.com` only to the internal network, and the public site `www.brand-a.example` is served by the same Jahia. A visitor on the public hostname requests:

```
https://www.brand-a.example/cms/render/live/en/sites/intranet/home.html
```

Without the module, Jahia returns the intranet's live home page, because the proxy restricted the hostname but the renderer trusts the path. With the module, the path addresses site `intranet` while the hostname maps to `brand-a`, so the request is answered 404. The short SEO form (`/en/sites/intranet/home.html`) and the file form (`/files/live/sites/intranet/...`) are covered the same way.

Request forms the filter checks, all against the same decoded path that Jahia renders from:

- the percent-encoded `/sites/` segment, e.g. `%73ites` for `sites`;
- the `/sites` node itself, as in `/sites.html.ajax` or `/sites/.html.ajax` (and their `{}` and suffix forms). That path names no site key, but on Jahia 8.2.3.2 the node's view lists every site's title, server name and top-level nodes over any hostname, so it is refused on a mapped hostname (measured). A page that merely contains a `sites` segment under the visitor's own site is not affected. Known over-block: a top-level path of the form `/<one-segment>/sites.<ext>`, such as a static asset at exactly `/<x>/sites.css`, looks like the SEO form and is refused;
- FORWARD dispatches as well as REQUEST, which matters where the SEO inbound rule forwards and decodes the path a second time (Jahia 8.2.3.2);
- the JCR expanded-name form `{}name` (empty namespace), which addresses the same node as `name`; on released 8.2.3.7 the path `/cms/render/live/en/sites/%7B%7D<other>/home.html` served the other site before this check was added (measured);
- a trailing `/` and a trailing `/*`, which are removed before the site key is read, as the renderer does;
- trailing dots in the hostname, which the module strips, so `site.example.` counts as `site.example`. Letter case in the hostname is not handled by the module: Jahia's own hostname lookup compares it case-insensitively;
- the site-selecting request parameters described in "Request parameters" below.

## Request parameters

Once the path has passed, two families of request parameter are checked against the hostname's site. The check runs on live requests whose hostname maps to a site, not on authoring paths or error pages.

- **`jsite=<site node identifier>`**: the renderer uses it to swap the site the page is rendered in. A request is refused unless the identifier is the hostname's site or `systemsite`. Every non-blank value counts when the parameter is repeated. A blank `jsite` is ignored, since Jahia answers that request with a 500 of its own.
- **`jarea=<node identifier>`**: on an ajax render of a page (`<page>.html.ajax?jarea=<uuid>`), Jahia renders the node the identifier names inside the page. The render layer refuses it when the node belongs to another site than the page's own (`systemsite` and nodes outside `/sites`, such as template areas, excepted), or is `/sites` or the repository root. This check applies on every hostname, mapped or not: Jahia's HTML cache stores the result under the page without the parameter, so a foreign node rendered once on an unmapped hostname would then be served to the page's own visitors. A `jarea` naming no readable node is let through, since Jahia then fails to render it as well.
- **Search parameters, `src_*`**: Jahia's search module reads them into its `SearchCriteria`. A request is a potential search when a `src_*` parameter with a non-blank value names a real property of `SearchCriteria`, read from the class at runtime (nested forms such as `src_properties(...)` count). Every potential search has its scope judged, whether or not Jahia would actually run it. The request is refused when that scope reaches another existing site, reaches every site (`-all-`, a blank element next to a non-blank one, a path outside `/sites/<key>`), or the search has no site or path scope at all, which would search the whole repository. Unknown `src_*` parameters (such as `src_ref=newsletter`) and parameters with only blank values are ignored, so tracking parameters keep working. Consequence: a request whose only `src_*` parameter is a criterion without a scope, such as `src_languages.values=en`, is answered 404. Only the spellings Jahia applies count as a scope: `src_sites`, `src_sites.value`, `src_sites.values` and `src_sites.values[n]`, and `src_pagePath` or `src_filePath`, bare or with `.value`. Other spellings set no scope in Jahia (measured on 8.2.3.2: `src_sites.zz`, `src_sites(x)`, `src_sites.Values`, `src_pagePath.values` and `src_filePath.values` each searched every site), so a search scoped only by them is refused; the sites they name are still checked.

The rules are in the package-private class `SearchParameterCheck`. Trade-off: a deliberate "search all sites" feature on a mapped hostname now gets 404, and so does a scope-less search criterion. The site's own search form sends its own site and keeps working.

If the site identifier cannot be read for `jsite`, the request is let through with a WARN (see Diagnosing).

## How it runs

- The filter is registered as an OSGi `AbstractServletFilter` with order 0.5, for REQUEST and FORWARD dispatches.
- Its order places it after Jahia's Shiro filter (order -3) and the JCR session filter (order -1), and before `UrlRewriteFilter` (order 1). Those orders are defined in jahia-impl's `applicationcontext-basejahiaconfig.xml`, as checked against jahia-impl 8.2.2.1. Nothing in this build verifies them, so they need re-checking when moving to a new Jahia line.
- When it activates it logs one INFO line naming its order and dispatcher types: `Cross-site access filter active: order 0.5, dispatcher types [FORWARD, REQUEST]`.

## Two layers

The module has two layers, both in the same bundle.

- **Layer 1, the servlet filter** (`CrossSitesFilter`, order 0.5, REQUEST and FORWARD) reads the URL and the request parameters as strings. It is the only layer that sees `/files`, `/repository`, actions (`*.do`, POST) and search hits: none of those enter the render chain, and search results are printed by a JSP rather than rendered as resources. Its activation line is `Cross-site access filter active: order 0.5, dispatcher types [FORWARD, REQUEST]`.
- **Layer 2, the render filter** (`CrossSitesRenderFilter`, priority -10, live mode, main resource only) reads no string. It takes the site of the main resource as Jahia resolved it, and the render-context site, which Jahia sets from the path, the hostname or `jsite`. It refuses when either is another existing site than the hostname's (`systemsite` excepted), and it refuses the main resource `/` (the repository root) and `/sites`, which list every site. It also judges `jarea` (see Request parameters), the one parameter that makes Jahia render a node of its choice inside the page. Its activation line is `Cross-site access render filter active: priority -10.0, live mode, main resource only`.

Neither value is enough alone: a foreign path shows in the node's site, and `jsite` shows in the render-context site. Layer 2 judges what Jahia resolved, so a new spelling of a path cannot slip past it for rendered pages. Layer 1 stays because it covers what layer 2 never sees. Measured on 8.2.3.2: with layer 1's render checks disabled, layer 2 alone refused every rendered bypass, but a foreign `/files` PDF and a foreign search were stopped only by layer 1.

Content that a page includes (content references, systemsite content) is not checked by layer 2, on purpose: a page legitimately includes systemsite content and references to another site, and only the main resource decides the page.

A block by layer 2 answers 404 through `PathNotFoundException`, which Jahia's error handling turns into the hostname's site's error page, as for layer 1. Measured on 8.2.3.2, that page is byte-identical to a genuine 404 of that site. The block line of layer 2 carries `(render layer)`; layer 1's block lines carry no layer name. Both layers stand down on an unmapped, localhost or empty hostname, during error dispatches, and when a lookup fails (rate-limited WARN). The `jarea` check is the exception to the hostname rule: it compares the node with the page's own site and applies on every hostname.

## What a visitor sees when blocked

A blocked request receives HTTP 404 and the error page of the site the visitor is on, the site their hostname maps to. The filter sets the request attribute `siteKey` to that site before it sends the 404, and Jahia's error servlet reads that attribute before it resolves the URL (per the maintainers' reading of Jahia's source). The page therefore carries that site's branding and language, and nothing from the blocked site.

This is not observable in the test suite, because both test sites use the same template set (`dx-base-demo-templates`), so the suite cannot tell a block page from a missing page on the mapped site.

A blocked request and a request for a site key that does not exist are meant to look alike. For an unknown key, Jahia falls back to the hostname's site and answers 404 on its own. This has not been compared for every URL form. The earlier observation on `/files/live` URLs, which found Jahia's own 404 generic there, was made before the `siteKey` attribute was added and has not been re-measured.

Accessibility of that page is decided by the template set of the site the visitor is on, not by this module. Measured on Jahia 8.2.3.2 with `dx-base-demo-templates` only. In that measurement both pages (the template's 404 and Jahia's generic one) have a `<title>`, an `<h1>`, an explanation and a link back to the home page. Neither declares `<html lang>` (WCAG 3.1.1, level A), and neither has a `<main>` landmark (WCAG 1.3.1 and 2.4.1, level A). Fix those in the template set or report them against the platform.

Not assessed for the error page: contrast (1.4.3, 1.4.6), reflow (1.4.10), focus visibility (2.4.7, 2.4.13), target size (2.5.5, 2.5.8), and link purpose (2.4.4, 2.4.9). Results for these on other templates or other Jahia versions are unknown.

## What it does NOT cover

- **It is a URL control, not an authorization layer.** It checks the site named in the URL path and the request parameters listed above. Anything else that addresses a node by a parameter, a body field or a UUID is not checked by this module and is governed by Jahia's ACLs and API security. Examples: GraphQL, which takes the node path in the request body; node-by-UUID APIs; and endpoints that take the node path in the query string other than `jsite` and `src_*`, such as `/cms/initializers/<workspace>/<lang>?path=/sites/<other>/...`. Measured on Jahia 8.2.3.2 and 8.2.3.7 with default configuration: a guest receives "Permission denied" from the GraphQL `jcr` field.
- **Only authoring and default-workspace URLs are exempt.** Those are `/cms/*/default`, `/files/default`, `/files/{workspace}` and `/repository/default`. `/files/{workspace}` is the placeholder CKEditor uses, which the file servlet maps to the default workspace. Those URLs are left to Jahia's login and ACLs. Measured on 8.2.3.2 and 8.2.3.7 with default configuration: a guest gets a 404 or a login redirect on them. An authenticated editor can open the authoring and default-workspace views of any site from a back-office hostname. A live view of another site (`/cms/render/live/...`) is blocked for editors as it is for anyone else.
- **`systemsite` is never blocked.** It holds content shared by every site.
- **A path naming a site that does not exist is not blocked.** Jahia answers 404 itself.
- **The control is driven by the hostname and stands down when the hostname maps to no site.** A bare IP address, an internal load-balancer name, or `localhost` is not restricted. Anyone who can reach the server under such a name can read any site.
- **Fail-open on lookup errors.** If the site list or the hostname lookup cannot be read, or the site list is empty, requests are let through unchecked. If the identifier of a site needed for `jsite` cannot be read, that request is let through as well. This is logged (see Diagnosing below).
- **Error pages are not enforced.** While Jahia renders an error page, the filter does not check the request. The error servlet may forward to a site-level error page under `/errors/sites/<key>/`, which must not be re-blocked into a bare 404. Whether a visitor can reach another site's error page this way was not measured.
- **No configuration and no per-site switch.** The only way to turn the protection off is to stop the bundle.
- **One defence depends on the Jahia core version.** Jahia core's own `MalformedRequestFilter` (which rejects double-encoded and non-normalised URLs with 400) is present in 8.2.3.7 and in the 8-SNAPSHOT image used for testing (an 8.2.4.0-SNAPSHOT build). It is absent from jahia-impl 8.2.3.2 and earlier. The module does not rely on it: it also checks forwarded requests, which blocks the double-encoding bypass on 8.2.3.2. On 8.2.3.2, dot-segment requests are still refused with 400 by a layer before the module; that layer was not identified, and it is not attributed to `MalformedRequestFilter`.

## Installation and supported versions

- **Jahia:** 8.2.3.2 or later. The bundle declares `Jahia-Required-Version: 8.2.3.2` and is built against the `jahia-modules` parent 8.2.3.2. It does not install on earlier Jahia versions.
- **Java:** the bundle targets Java 11. Build with JDK 11 or later.
- **Tested end to end** on Jahia 8-SNAPSHOT and 8.2.3.2 for the current change set (all 36 Cypress tests green on both, with both layers active). Earlier measurements, made before the current parent, also covered 8.2.3.7.
- **Build:** `mvn clean install` at the repository root produces `target/prevent-cross-sites-access-<version>.jar`, where `<version>` is the version in `pom.xml`. The unit tests run as part of the build and need no Docker. The tests use Java 11 APIs and Mockito 5. The released manifest does not carry `Jahia-Source-Folders`, so the builder's checkout path is not published (the parent's header is removed in `pom.xml`).
- **Deploy:** install the jar as any other Jahia 8 module (Module Manager upload, or the modules deploy directory of your installation). No site or module settings are needed.
- **Disabling:** stop the bundle (`Bundle-Name: Prevent Cross Sites Access`, symbolic name `prevent-cross-sites-access`). Both layers are in this bundle, so stopping it disables both. There is no other switch.

## Upgrading from 1.0.0

Before 2.0.0, 1.0.0 (tag `1_0_0`) was the only release. It was built against the `jahia-modules` parent 8.1.0.0 and installs on Jahia 8.1 and later. The differences below are from that release to 2.0.0.

- **Minimum Jahia version: 8.1 became 8.2.3.2.** The bundle now declares `Jahia-Required-Version: 8.2.3.2` and targets Java 11. Installations below 8.2.3.2 must stay on 1.0.0 or upgrade Jahia first.
- **Status of a block: 400 became 404.** 1.0.0 answered 400, which revealed which site keys exist. Anyone alerting or monitoring on 400 from this module will stop seeing it; a block is now only visible in the log.
- **Body of a block: an empty body became an error page.** 1.0.0 sent no document at all. The page is now the 404 page of the visitor's own site (see "What a visitor sees when blocked").
- **FORWARD dispatches are checked** as well as REQUEST.
- **`systemsite` is now allowed.** 1.0.0 blocked it, which made shared content fail on every site that referenced it.
- **Authoring and default-workspace URLs are no longer blocked.** Jahia login and ACLs govern them.
- **Trailing-dot hostnames and the `{}` expanded-name form are now enforced.**
- **Trailing `/` and `/*` are removed before the site key is read.**
- **Site-selecting request parameters are checked.** A `jsite` naming another site's node is answered 404, and so is a potential search (a `src_*` parameter with a non-blank value naming a `SearchCriteria` property) whose scope reaches another site, every site, or the whole repository. A criterion without any scope, such as `src_languages.values=en` alone, is therefore answered 404 too. Unknown `src_*` parameters and blank values do not count.
- **`jarea` naming another site's node is answered 404, on every hostname.** After installing, flush Jahia's HTML cache once (for example from the Cache management page of the server administration): a page fragment cached before the upgrade may already contain another site's node.
- **The `/sites` node itself is blocked on a mapped hostname** (`/sites.html.ajax` and its forms). Whether 1.0.0 served it was not checked for this note.
- **Error pages are not enforced** (see "What it does NOT cover").
- **The urlrewrite rule file has been removed.** 1.0.0 used one.
- **A second layer checks the main resource of each rendered page** (see "Two layers"). A page whose main resource is another site's, or the repository root or the `/sites` node, is refused whichever path spelling reached it. On 8.2.3.2 the repository root rendered 0 bytes unguarded (measured).
- **Log output changed:**
  - a path block line now names the hostname the server saw (trailing dots removed) and ends with `referer: <value>`, or `referer: -` when the browser sent none;
  - a parameter block line has the form `parameter '<name>' selects <target> but hostname '<host>' maps to site '<site>'`, followed by the path and the referer;
  - the path and referer are capped at 512 characters;
  - control, format (bidirectional and zero-width) and line-separator characters in logged values are replaced by `?`;
  - one INFO line announces the filter when it activates;
  - a hostname that maps to no site is logged at DEBUG;
  - the WARN for a failing lookup repeats at most every 10 minutes (1.0.0 logged it once);
  - an empty site list counts as a failure;
  - the site-identifier lookup used by `jsite` has its own WARN.

## Diagnosing a request the module blocked

1. **Get the exact URL and the hostname from whoever reports the problem.** A block is indistinguishable from a missing page for the visitor, so a description such as "the page does not load" is not enough.
2. **Check that the filter is active.** Run `log:get org.jahia.community.modules.CrossSitesFilter` in the Karaf console. The filter is active if the INFO line `Cross-site access filter active: order 0.5, dispatcher types [FORWARD, REQUEST]` appears in the log after the module started, and the render layer's line `Cross-site access render filter active: priority -10.0, live mode, main resource only` appears as well. If the logger is at INFO or finer and that line is missing, the filter is not active.
3. **Find the block line.** Logger `org.jahia.community.modules.CrossSitesFilter`, one line per blocked request. A path block has this form:

   ```
   Blocked cross-site access: path addresses site 'X' but hostname 'H' maps to site 'Y', path: <decoded path>, referer: <referer or ->
   ```

   A parameter block has this form:

   ```
   Blocked cross-site access: parameter 'P' selects <target> but hostname 'H' maps to site 'Y', path: <decoded path>, referer: <referer or ->
   ```

   `<target>` is `site 'Z'` for a site key, `site node '<uuid>'` for `jsite`, or `every site (...)` for a search with no scope or with a scope that covers every site. A path that addresses the `/sites` node itself reads `path addresses the sites root '/sites' but hostname 'H' maps to site 'Y'`.

   A render-layer block has this form:

   ```
   Blocked cross-site access (render layer): main resource resolves to <target> but hostname 'H' maps to site 'Y', main resource: <path>, referer: <referer or ->
   Blocked cross-site access (render layer): jarea selects <target> on a page of site 'Y', hostname 'H', main resource: <path>, referer: <referer or ->
   ```

   `<target>` is `site 'Z'` for the site of the main resource, `render-context site 'Z'` for the site Jahia rendered in, or `every site (the node '/')` or `every site (the node '/sites')` for the two listings.

   `X` is the site the URL addresses, `Y` the site the request's hostname maps to, and `H` the hostname the server saw, with trailing dots removed. The path is the decoded form. Control, format and line-separator characters are replaced by `?`; a path or referer longer than 512 characters is cut and followed by `…`. The referer is the page that linked to the blocked URL or issued the search, which is usually the page to fix.
4. **Jahia's `ErrorLoggingFilter` logs the 404 separately.** Use it to correlate the request.
5. **Check the hostname.** Compare `Y` with the `j:serverName` of site `Y`, and `H` with the `Host` value the server actually receives (a reverse proxy may rewrite it).
6. **Check the link or the search.** A page of site `Y` that references live content under `/sites/X/...` is refused by design. Such references must point at `X`'s own hostname. Shared content should live in `systemsite`, which is exempt. A search form on site `Y` that selects another site is refused by design; the site's own search form sends its own site. A criterion without any scope, such as `src_languages` alone, is refused too.
7. **No "Blocked" line but a 404:** if the logger is at INFO or finer (step 2), this module did not block the request. The 404 comes from Jahia (unknown key, unknown site, or an ordinary missing page). If the logger is above INFO, the absence of the line proves nothing: raise the level as in step 8 and repeat the request.
8. **Raise the logger only while diagnosing.** In the Karaf console, run `log:set DEBUG org.jahia.community.modules.CrossSitesFilter`, and restore it with `log:set INFO org.jahia.community.modules.CrossSitesFilter` (or the level `log:get` showed before). At DEBUG, each request whose hostname maps to no site logs `Hostname '<host>' maps to no site, cross-site check skipped`. Hostnames `localhost` and empty ones are not looked up and are not logged. The line is written per request.
9. **WARN "letting requests through unchecked":** the site list, the hostname lookup, the site-identifier lookup, or the site of the main resource failed, so the protection is off for requests in that state. The WARN is logged when the failure starts and then at most every 10 minutes while it lasts; the exception is attached to the WARN when there is one. An INFO "recovered" line follows when the lookup works again. Check the repository and database health.
10. **Unmapped hostnames are silent by default.** A request to a bare IP, an internal load-balancer name or `localhost` is not checked and is not logged at INFO.

## Developers

See [tests/README.md](tests/README.md) for the Cypress end-to-end suite, the Docker harness and its prerequisites. See [AGENTS.md](AGENTS.md) for the rules that apply when changing the filter.

Unit tests (JUnit 5, JDK 11 or later, no Docker needed):

```bash
mvn clean install
```

The JUnit tests cover what HTTP cannot observe, since a block and an unknown site key both answer 404: the site-key resolver, the `/sites` node check, the decision matrix, the authoring-path pattern, hostname normalisation, the site-selecting request parameters (`SearchParameterCheck` and `jsite`), the render layer's decision, and failure handling.

SonarQube analysis: `mvn clean install sonar:sonar` works as it is on Java 11. The parent brings `sonar-maven-plugin` 5.6.0, which provisions its own Java runtime.

## License

MIT. See [LICENSE.txt](LICENSE.txt).
