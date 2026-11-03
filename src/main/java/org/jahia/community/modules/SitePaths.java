package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.services.sites.JahiaSitesService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The servlet layer's string reading of a request path: which site it addresses, whether it is an
 * authoring path, whether it names the {@code /sites} node itself. Mirrors {@code URLResolver}
 * (jahia-impl 8.2.2.1); the render layer does not need it, as it reads what Jahia resolved.
 */
final class SitePaths {

    static final String SITES_PATH_PART = JahiaSitesService.SITES_JCR_PATH + '/';

    /** Name of the repository node every site lives under ({@code /sites}). */
    static final String SITES_NODE_NAME = "sites";

    /**
     * Authoring / default-workspace paths: /cms/{edit,editframe,render,contribute,...}/default,
     * /repository/default, /files/default. FileServlet maps the CKEditor placeholder workspace
     * {workspace} to the default (edit) workspace, so /files/{workspace} is exempt as well.
     */
    private static final Pattern AUTHORING_PATH = Pattern.compile(
            "^/(?:(?:cms/[^/]+|repository)/default|files/(?:default|\\{workspace\\}))(?:/|$)");

    /** Servlets whose URL is {@code /<servlet>/<workspace>} followed by a JCR path. */
    private static final Set<String> WORKSPACE_SERVLETS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("files", "repository")));

    private SitePaths() {
    }

    static boolean isAuthoringPath(String path) {
        return path != null && AUTHORING_PATH.matcher(path).find();
    }

    /**
     * The path as the renderer will read it. JCR reads one leading {@code {}} (empty namespace) on a
     * path segment as no marker at all; not recursive on purpose, {@code {}{}x} becomes {@code {}x},
     * which JCR rejects too. Then, in the renderer's order, one trailing "/" goes
     * ({@code URLResolver#normalizeUrlPathInfo}) and a trailing "/*" goes ({@code resolveNode}).
     */
    static String normalizeJcrPath(String path) {
        String result = StringUtils.defaultString(path).replace("/{}", "/");
        if (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("/*")) {
            result = result.substring(0, result.length() - 2);
        }
        return result;
    }

    /**
     * Mirrors {@code URLResolver#resolveSiteKeyFromPath} (jahia-impl 8.2.2.1), narrowed to keys
     * that name an existing site.
     *
     * @return the site the path addresses, or null if it addresses none
     */
    static String resolveSiteKeyFromPath(String path, Collection<String> existingSites) {
        if (path == null || existingSites == null || existingSites.isEmpty()
                || !path.contains(SITES_PATH_PART)) {
            return null;
        }
        final Set<String> sites = SiteDirectory.toSet(existingSites);

        // ".../sites/<key>/..." - the common case.
        final String keyBetweenSlashes = StringUtils.substringBetween(path, SITES_PATH_PART, "/");
        if (StringUtils.isNotEmpty(keyBetweenSlashes)) {
            return sites.contains(keyBetweenSlashes) ? keyBetweenSlashes : null;
        }

        // ".../sites/<key>", ".../sites/<key>.<type>" or ".../sites/<key>.<template>.<type>".
        // A site key may itself contain a dot, so where the key ends cannot be decided by looking
        // at the string: the candidates (the whole ending, then each cut at a dot) are tried
        // against the real sites, longest first. The path is attacker-chosen, so candidates longer
        // than the longest site name are skipped without allocating them.
        final String pathEnding = StringUtils.substringAfter(path, SITES_PATH_PART);
        final int longestName = sites.stream().mapToInt(String::length).max().orElse(0);
        int end = pathEnding.length();
        while (end > 0) {
            if (end <= longestName && sites.contains(pathEnding.substring(0, end))) {
                return pathEnding.substring(0, end);
            }
            end = pathEnding.lastIndexOf('.', end - 1);
        }
        return null;
    }

    /**
     * Whether the first {@code sites} segment of a normalised path is the {@code /sites} node itself,
     * at the start of the JCR path: {@code /sites}, {@code /sites.<ext...>} or {@code /sites/.<ext...>}.
     * Called only when no site key resolved, so {@code /sites/<own>/home/sites.html} never gets here.
     */
    static boolean addressesSitesRoot(String jcrPath) {
        final String root = JahiaSitesService.SITES_JCR_PATH;
        int at = jcrPath.indexOf(root);
        while (at >= 0) {
            final String rest = jcrPath.substring(at + root.length());
            if (rest.isEmpty() || rest.charAt(0) == '.' || rest.charAt(0) == '/') {
                final boolean isNodeItself = rest.isEmpty() || rest.charAt(0) == '.' || rest.equals("/") || rest.startsWith("/.");
                return isNodeItself && isUrlPrefixBeforeJcrPath(jcrPath.substring(0, at));
            }
            at = jcrPath.indexOf(root, at + 1);
        }
        return false;
    }

    /**
     * Path prefixes a JCR path follows in a URL: {@code /cms/<servlet>/<workspace>[/<lang>]},
     * {@code /files/<workspace>}, {@code /repository/<workspace>}, the SEO {@code /<lang>}, or none.
     * Every segment of the prefix is non-empty.
     */
    private static boolean isUrlPrefixBeforeJcrPath(String prefix) {
        if (prefix.isEmpty()) {
            return true;
        }
        if (prefix.charAt(0) != '/') {
            return false;
        }
        final String[] segments = prefix.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return false;
            }
        }
        switch (segments.length) {
            case 1:
                return true;
            case 2:
                return WORKSPACE_SERVLETS.contains(segments[0]);
            case 3:
            case 4:
                return "cms".equals(segments[0]);
            default:
                return false;
        }
    }

}
