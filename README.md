# prevent-cross-sites-access

A Jahia 8 OSGi module that stops a request from reading one site's live content through another site's hostname. It is a single servlet filter, `org.jahia.community.modules.CrossSitesFilter`, and has no configuration.

## What it protects against

Jahia serves every site from one server. A site is normally reached through its hostname (`j:serverName`), but a request can also name any site in the URL path, as in `/sites/<key>/...`. Without this module, Jahia renders the site named in the path regardless of the hostname the request arrived on.

Example: a reverse proxy exposes `intranet.example.com` only to the internal network, and the public site `www.brand-a.example` is served by the same Jahia. A visitor on the public hostname requests:

```
https://www.brand-a.example/cms/render/live/en/sites/intranet/home.html
```

Without the module, Jahia returns the intranet's live home page, because the proxy restricted the hostname but the renderer trusts the path. With the module, the path addresses site `intranet` while the hostname maps to `brand-a`, so the request is answered 404. The short SEO form (`/en/sites/intranet/home.html`) and the file form (`/files/live/sites/intranet/...`) are covered the same way.

Request forms the filter checks, all against the same decoded path that Jahia renders from:

- the percent-encoded `/sites/` segment, e.g. `%73ites` for `sites`;
- FORWARD dispatches as well as REQUEST, which matters where the SEO inbound rule forwards and decodes the path a second time (Jahia 8.2.3.2);
- the JCR expanded-name form `{}name` (empty namespace), which addresses the same node as `name`; on released 8.2.3.7 the path `/cms/render/live/en/sites/%7B%7D<other>/home.html` served the other site before this check was added (measured);
- trailing dots and letter case in the hostname.

## What a visitor sees when blocked

A blocked request receives HTTP 404 and an error page rendered by Jahia. Jahia's error servlet chooses the page from the site named in the URL path, so the visitor gets the same kind of page as for a missing page under that site. The page carries none of the blocked content. With `dx-base-demo-templates` (the template set measured) no site name appears on it. Another template set may show its own branding, including a site name, on a page served under a hostname that does not belong to that site.

It does not fully hide whether a site key exists, and no module can: Jahia itself picks the error page by whether the site in the path exists. Measured with this module out of play, a missing page under an existing site gets that site's 404 template, while an unknown site key gets Jahia's generic 404 page. A blocked request shows the existing-site template, so it tells a prober no more than Jahia already does. (On `/files/live` URLs Jahia's own 404s are generic either way, so there a block does show the site-template page; the information is the same one render URLs already give away.)

Accessibility of that page is decided by the site's template set (for a site with a 404 template) or by Jahia (its generic error page), not by this module. Measured on Jahia 8.2.3.2 with `dx-base-demo-templates`: both pages have a `<title>`, an `<h1>`, an explanation and a link back to the home page, but neither declares `<html lang>` (WCAG 3.1.1) nor a `<main>` landmark. Fix those in the template set or report them against the platform.

## What it does NOT cover

- **It is a URL control, not an authorization layer.** Content fetched through APIs that take the node path in the request body (GraphQL, for example) is governed by Jahia's API security and ACLs. Measured on Jahia 8.2.3.2 and 8.2.3.7 with default configuration: a guest receives "Permission denied" from the GraphQL `jcr` field.
- **Only authoring and default-workspace URLs are exempt.** Those are `/cms/*/default`, `/files/default` and `/repository/default`; they are left to Jahia's login and ACLs. Measured on 8.2.3.2 and 8.2.3.7 with default configuration: a guest gets a 404 or a login redirect on them. An authenticated editor can open the authoring and default-workspace views of any site from a back-office hostname. A live view of another site (`/cms/render/live/...`) is blocked for editors as it is for anyone else.
- **`systemsite` is never blocked.** It holds content shared by every site.
- **A path naming a site that does not exist is not blocked.** Jahia answers 404 itself.
- **The control is driven by the hostname and stands down when the hostname maps to no site.** A bare IP address, an internal load-balancer name, or `localhost` is not restricted. Anyone who can reach the server under such a name can read any site.
- **Hostname variants count as the site.** Trailing dots are stripped by the module, so `site.example.` counts as `site.example`; letter case is folded by Jahia's own hostname lookup, so `SITE.example` does too.
- **Fail-open on lookup errors.** If the site list or the hostname lookup cannot be read, or the site list is empty, requests are let through unchecked. This is logged (see Diagnosing below).
- **Error pages are not enforced.** While Jahia renders an error page, the filter does not check the request. The error servlet may forward to a site-level error page under `/errors/sites/<key>/`, which must not be re-blocked into a bare 404. Whether a visitor can reach another site's error page this way was not measured.
- **No configuration and no per-site switch.** The only way to turn the protection off is to stop the bundle.
- **One defence depends on the Jahia core version.** Jahia core's own `MalformedRequestFilter` (which rejects double-encoded and non-normalised URLs with 400) is present in 8.2.3.7 and in the 8-SNAPSHOT image used for testing (an 8.2.4.0-SNAPSHOT build). It is absent from jahia-impl 8.2.3.2 and earlier. The module does not rely on it: it also checks forwarded requests, which blocks the double-encoding bypass on 8.2.3.2. On 8.2.3.2, dot-segment requests are still refused with 400 by a layer before the module; that layer was not identified, and it is not attributed to `MalformedRequestFilter`.

## Installation and supported versions

- **Jahia:** 8.1 or later. The module is built against the `jahia-modules` parent 8.1.0.0.
- **Tested end to end** on Jahia 8.2.3.2, 8.2.3.7 and the 8-SNAPSHOT image. Not tested on 8.1.x.
- **Build:** `mvn clean install` at the repository root produces `target/prevent-cross-sites-access-1.0.1-SNAPSHOT.jar`. The unit tests run as part of the build and need no Docker.
- **Deploy:** install the jar as any other Jahia 8 module (Module Manager upload, or the modules deploy directory of your installation). No site or module settings are needed.
- **Disabling:** stop the bundle (`Bundle-Name: Prevent Cross Sites Access`, symbolic name `prevent-cross-sites-access`). There is no other switch.

## Upgrading from an earlier build

These are the user-visible differences between earlier builds and the current one. This README does not map them to version numbers.

- **Status of a block: 400 became 404.** Earlier builds answered 400, which revealed which site keys exist. Anyone alerting or monitoring on 400 from this module will stop seeing it; a block is now only visible in the log.
- **Body of a block: an empty body became an error page.** Earlier builds sent no document at all. The page is now Jahia's error page for that URL (see "What a visitor sees when blocked").
- **FORWARD dispatches are checked** as well as REQUEST.
- **`systemsite` is now allowed.** Earlier builds blocked it, which made shared content fail on every site that referenced it.
- **Authoring and default-workspace URLs are no longer blocked.** Jahia login and ACLs govern them.
- **Trailing-dot hostnames and the `{}` expanded-name form are now enforced.**
- **Error pages are not enforced** (see "What it does NOT cover").
- **The urlrewrite rule file has been removed.** Earlier builds used one.
- **Log output changed:** the INFO line now names the hostname the server saw and caps the path at 512 characters; a hostname that maps to no site is logged at DEBUG; the WARN for a failing lookup repeats at most every 10 minutes (it used to be logged once); an empty site list counts as a failure.

## Diagnosing a request the module blocked

1. **Get the exact URL and the hostname from whoever reports the problem.** A block is indistinguishable from a missing page for the visitor, so a description such as "the page does not load" is not enough.
2. **Find the INFO line.** Logger `org.jahia.community.modules.CrossSitesFilter`, one line per blocked request:

   ```
   Blocked cross-site access: path addresses site 'X' but hostname 'H' maps to site 'Y', path: <decoded path>
   ```

   `X` is the site the URL addresses, `Y` the site the request's hostname maps to, and `H` the hostname the server saw, with trailing dots removed. The path is the decoded form; control characters are replaced by `?`, and a path longer than 512 characters is cut and followed by `…`.
3. **Jahia's `ErrorLoggingFilter` logs the 404 separately.** Use it to correlate the request.
4. **Check the hostname.** Compare `Y` with the `j:serverName` of site `Y`, and `H` with the `Host` value the server actually receives (a reverse proxy may rewrite it).
5. **Check the link.** A page of site `Y` that references live content under `/sites/X/...` is refused by design. Such references must point at `X`'s own hostname. Shared content should live in `systemsite`, which is exempt.
6. **No "Blocked" line but a 404:** this module did not block the request. The 404 comes from Jahia (unknown key, unknown site, or an ordinary missing page).
7. **WARN "letting requests through unchecked":** the site list or the hostname lookup failed, so the protection is off for requests in that state. The WARN is logged when the failure starts and then at most every 10 minutes while it lasts; the exception is attached to the WARN when there is one. An INFO "recovered" line follows when the lookup works again. Check the repository and database health.
8. **Unmapped hostnames are silent by default.** With the logger `org.jahia.community.modules.CrossSitesFilter` at DEBUG, each request whose hostname maps to no site logs `Hostname '<host>' maps to no site, cross-site check skipped`. Hostnames `localhost` and empty ones are not looked up and are not logged. Raise the level only while diagnosing; the line is written per request.

## Developers

See [tests/README.md](tests/README.md) for the Cypress end-to-end suite, the Docker harness and its prerequisites.

Unit tests (JUnit 5, no Docker needed):

```bash
mvn clean install
```

The JUnit tests cover what HTTP cannot observe, since a block and an unknown site key both answer 404: the site-key resolver, the decision matrix, the authoring-path pattern, hostname normalisation and failure handling.

## License

MIT. See [LICENSE.txt](LICENSE.txt).
