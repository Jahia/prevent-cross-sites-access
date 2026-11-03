# prevent-cross-sites-access

A Jahia 8 OSGi module that stops a request from reading one site's live content through another site's hostname. It is a single servlet filter, `org.jahia.community.modules.CrossSitesFilter`, and has no configuration.

## What it protects against

Jahia serves every site from one server. A site is normally reached through its hostname (`j:serverName`), but a request can also name any site in the URL path, as in `/sites/<key>/...`. Without this module, Jahia renders the site named in the path regardless of the hostname the request arrived on.

Example: a reverse proxy exposes `intranet.example.com` only to the internal network, and the public site `www.brand-a.example` is served by the same Jahia. A visitor on the public hostname requests:

```
https://www.brand-a.example/cms/render/live/en/sites/intranet/home.html
```

Without the module, Jahia returns the intranet's live home page, because the proxy restricted the hostname but the renderer trusts the path. With the module, the path addresses site `intranet` while the hostname maps to `brand-a`, so the request is answered 404. The short SEO form (`/en/sites/intranet/home.html`) and the file form (`/files/live/sites/intranet/...`) are covered the same way.

## What a visitor sees when blocked

A blocked request receives HTTP 404 and Jahia's own error page for that URL, which is the page a visitor gets for a missing page on an existing site. The response does not name either site and carries none of the blocked content.

It does not fully hide whether a site key exists, and no module can: Jahia itself picks the error page by whether the site in the path exists. Measured with this module out of play, a missing page under an existing site gets that site's 404 template, while an unknown site key gets Jahia's generic 404 page. A blocked request shows the existing-site template, so it tells a prober no more than Jahia already does. (On `/files/live` URLs Jahia's own 404s are generic either way, so there a block does show the site-template page; the information is the same one render URLs already give away.) Earlier builds of this module answered 400, an additional and much plainer signal; that is gone.

Accessibility of that page is decided by the site's template set (for a site with a 404 template) or by Jahia (its generic error page), not by this module. Measured on Jahia 8.2.3.2 with `dx-base-demo-templates`: both pages have a `<title>`, an `<h1>`, an explanation and a link back to the home page, but neither declares `<html lang>` (WCAG 3.1.1) nor a `<main>` landmark. Fix those in the template set or report them against the platform. Before this version the module answered with an empty body, no document at all.

## What it does NOT cover

- **It is a URL control, not an authorization layer.** Content fetched through APIs that take the node path in the request body (GraphQL, for example) is governed by Jahia's API security and ACLs. By default a guest receives "Permission denied" from the GraphQL `jcr` field.
- **Only live content is enforced.** Authoring and default-workspace paths (`/cms/*/default`, `/files/default`, `/repository/default`) are left to Jahia's login and ACLs. A guest gets 404 or a login redirect on them, so nothing is opened to visitors, but an authenticated editor can preview and edit any site from a back-office hostname.
- **`systemsite` is never blocked.** It holds content shared by every site.
- **A path naming a site that does not exist is not blocked.** Jahia answers 404 itself.
- **The control is driven by the hostname and stands down when the hostname maps to no site.** A bare IP address, an internal load-balancer name, or `localhost` is not restricted. Anyone who can reach the server under such a name can read any site.
- **Hostname variants count as the site.** Trailing dots are stripped by the module, so `site.example.` counts as `site.example`; letter case is folded by Jahia's own hostname lookup, so `SITE.example` does too.
- **Fail-open on lookup errors.** If the site list or the hostname lookup cannot be read, requests are let through unchecked. This is logged (see Diagnosing below).
- **No configuration and no per-site switch.** The only way to turn the protection off is to stop the bundle.
- **Jahia core version dependency for one defence.** Jahia core gained its own `MalformedRequestFilter` (which rejects double-encoded and non-normalised URLs with 400) after 8.2.3.2. It is absent from jahia-impl 8.2.3.2 and earlier and present in 8.2.3.7 and 8.2.4.0-SNAPSHOT. This module does not rely on it: it also checks forwarded requests, which blocks the double-encoding bypass on 8.2.3.2.

## Installation and supported versions

- **Jahia:** 8.1 or later. The module is built against the `jahia-modules` parent 8.1.0.0.
- **Tested end to end** on Jahia 8.2.3.2, 8.2.3.7 and the 8-SNAPSHOT image. Not tested on 8.1.x.
- **Build:** `mvn clean install` at the repository root produces `target/prevent-cross-sites-access-1.0.1-SNAPSHOT.jar`. The unit tests run as part of the build and need no Docker.
- **Deploy:** install the jar as any other Jahia 8 module (Module Manager upload, or the modules deploy directory of your installation). No site or module settings are needed.
- **Upgrading from an older build:** the module no longer ships a URL rewrite rule file. Earlier builds used one; it has been removed.
- **Disabling:** stop the bundle (`Bundle-Name: Prevent Cross Sites Access`, symbolic name `prevent-cross-sites-access`). There is no other switch.

## Diagnosing a request the module blocked

1. **Find the INFO line.** Logger `org.jahia.community.modules.CrossSitesFilter`, one line per blocked request:

   ```
   Blocked cross-site access: path addresses site 'X' but hostname maps to site 'Y', path: <decoded path>
   ```

   `X` is the site the URL addresses, `Y` the site the request's hostname maps to. The path is the decoded form; control characters are replaced by `?`.
2. **Jahia's `ErrorLoggingFilter` logs the 404 separately.** Use it to correlate the request.
3. **Check the hostname.** Compare `Y` with the `j:serverName` of the site, and check the `Host` value the server actually receives (a reverse proxy may rewrite it).
4. **Check the link.** A page of site `Y` that references live content under `/sites/X/...` is refused by design. Such references must point at `X`'s own hostname. Shared content should live in `systemsite`, which is exempt.
5. **No "Blocked" line but a 404:** this module did not block the request. The 404 comes from Jahia (unknown key, unknown site, or an ordinary missing page).
6. **WARN "letting requests through unchecked":** the site list or the hostname lookup failed, so the protection is off. The WARN carries the exception once. An INFO "recovered" line follows when the lookup works again. Check the repository and database health.
7. **Nothing is logged for an unmapped hostname, bare IP, or `localhost`.** That is the documented fail-open behaviour, not a fault.

## Developers

See [tests/README.md](tests/README.md) for the Cypress end-to-end suite, the Docker harness and its prerequisites.

Unit tests (JUnit 5, no Docker needed):

```bash
mvn clean install
```

The 84 JUnit cases cover what HTTP cannot observe, since a block and an unknown site key both answer 404: the resolver, the decision matrix, the authoring-path pattern, hostname normalisation and failure handling.

## License

MIT. See [LICENSE.txt](LICENSE.txt).
