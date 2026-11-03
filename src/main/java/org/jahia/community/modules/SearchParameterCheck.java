package org.jahia.community.modules;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.jahia.services.search.SearchCriteria;
import org.jahia.services.sites.JahiaSitesService;
import org.slf4j.LoggerFactory;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Decides whether the search parameters ({@code src_*}) of a request select a search scope beyond the
 * hostname's site and systemsite. String logic plus one cached introspection of {@code SearchCriteria}.
 *
 * <p><b>The rule.</b> A request is a <em>potential search</em> when one {@code src_} parameter with a
 * non-blank value starts with a real property of {@code org.jahia.services.search.SearchCriteria}
 * ({@code src_<property>...}). The property set is read from the class at runtime with
 * {@link Introspector} (read or write method, so nested forms such as
 * {@code src_properties(jnt:news).desc.value} count), and falls back to {@link #FALLBACK_PROPERTIES}
 * if the class cannot be introspected. For every potential search the effective scope is judged,
 * whether or not Jahia would actually run it; no scope at all is the whole repository and is refused.
 *
 * <p><b>Why not mirror {@code SearchCriteria#isEmpty()}.</b> Deciding which criteria make Jahia run a
 * search means keeping a list of criteria in step with Jahia, and one missing entry (node properties,
 * {@code src_properties(...)}, measured on 8.2.3.2) let a foreign search through. Judging the scope of
 * every potential search errs on the safe side: the worst case is a 404 for a request that names a
 * search property, carries a value, and sets no scope, which no Jahia search form produces.
 *
 * <p>What stays mirrored, read in jahia-impl {@code JAHIA_8_2_3_0} source and checked against the
 * jahia-impl 8.2.3.2 jar with commons-beanutils 1.9.4 (populating a real {@code SearchCriteria} the way
 * {@code SearchCriteriaFactory#getInstance} does):
 *
 * <ol>
 *   <li><b>Unknown properties.</b> {@code BeanUtilsBean#populate} skips a property the bean does not
 *       have, nested or not, so {@code src_ref=newsletter} changes nothing (measured: no exception,
 *       criteria unchanged). Property names are case-sensitive. Such parameters are ignored here.</li>
 *   <li><b>Blank values.</b> A blank value is no criterion for any {@code SearchCriteria} property
 *       ({@code isValueEmpty} is {@code isBlank}); a parameter with only blank values does not make a
 *       potential search. {@code JahiaJCRSearchProvider#buildSQL2Query} / {@code buildXpathQuery} use a
 *       site list only when {@code MultipleValue#isEmpty()} is false: a lone blank
 *       {@code src_sitesForReferences.values=} becomes {@code []} and is ignored; a lone blank
 *       {@code src_sites.values=} sets no scope. A blank element next to a non-blank one is kept and
 *       becomes the scope {@code /sites/} (every site): a repeated parameter ({@code ["siteB", ""]}) or a
 *       quoted empty token for {@code ArrayConverter} ({@code siteB,""}).</li>
 *   <li><b>Scope.</b> {@code filePath}, else {@code pagePath}, else {@code sites} plus
 *       {@code sitesForReferences}; with none of them the query has no scope. {@code -all-} as the first
 *       site is every site. {@code ResultsTag#getSearchCriteria} replaces a page path outside
 *       {@code /sites/} with {@code /sites/}.</li>
 * </ol>
 *
 * <p>Where the parsers are lax the check over-approximates: each parameter is judged as if BeanUtils
 * applied it last (the factory iterates a {@code HashMap}), and a site value is cut into candidate keys
 * more finely than any one parser does.
 */
final class SearchParameterCheck {

    /** Prefix of every parameter {@code SearchCriteriaFactory} copies onto the search criteria. */
    static final String SEARCH_PARAMETER_PREFIX = "src_";

    /** {@code SearchCriteria} properties holding site keys; only the first one scopes a search on its own. */
    private static final String SITES_PROPERTY = "sites";
    private static final Set<String> SITE_LIST_PROPERTIES = setOf(SITES_PROPERTY, "sitesForReferences");

    /** Path properties, in the order the provider prefers them; each takes precedence over the site list. */
    private static final List<String> PATH_PROPERTIES = Collections.unmodifiableList(Arrays.asList("filePath", "pagePath"));

    /** The boolean sub-property of the path properties, the only one that carries no path. */
    private static final String INCLUDE_CHILDREN_SUFFIX = ".includeChildren";

    /**
     * Where a site value is cut into candidate keys: every character {@code ArrayConverter}'s
     * {@code StreamTokenizer} does not keep in a word (it keeps letters, digits and {@code ._:- }), so
     * braces, quotes, {@code /}, {@code [n]} and any other punctuation separate keys.
     */
    private static final Pattern NON_KEY_CHARACTERS = Pattern.compile("[^\\p{L}\\p{N}._:\\-]+");

    /** Characters that leave nothing of an element for {@code ArrayConverter}: quotes and braces around it. */
    private static final Pattern WRAPPING_CHARACTERS = Pattern.compile("[\\s\"'{}]+");

    /** Site-list tokens that widen the scope instead of naming a site. */
    private static final Set<String> EVERY_SITE_TOKENS = setOf("-all-", "*", ".", "..");

    private static final Pattern SAME_NAME_SIBLING_INDEX = Pattern.compile("\\[\\d*\\]$");

    /**
     * The {@code SearchCriteria} bean properties of jahia-impl 8.2.3.2, read or write, as
     * {@link Introspector} reports them; used only if the class cannot be introspected at runtime.
     */
    static final Set<String> FALLBACK_PROPERTIES = setOf("created", "createdBy", "empty", "excludeFileReferences",
            "facetDefinitions", "facets", "filePath", "fileSearch", "fileType", "itemsPerPage", "languages",
            "lastModified", "lastModifiedBy", "limit", "nodeType", "offset", "orderings", "originSiteKey", "pagePath",
            "properties", "propertiesAll", "rawQuery", "siteSearch", SITES_PROPERTY, "sitesForReferences", "term", "terms");

    private SearchParameterCheck() {
    }

    /** Read once, on first use. The class literal does not initialise {@code SearchCriteria}. */
    private static final class CriteriaProperties {
        private static final Optional<Set<String>> INTROSPECTED = introspect();
        static final Set<String> NAMES = INTROSPECTED.orElse(FALLBACK_PROPERTIES);

        private static Optional<Set<String>> introspect() {
            try {
                final Set<String> names = new TreeSet<>();
                for (PropertyDescriptor property : Introspector.getBeanInfo(SearchCriteria.class, Object.class)
                        .getPropertyDescriptors()) {
                    if (property.getReadMethod() != null || property.getWriteMethod() != null) {
                        names.add(property.getName());
                    }
                }
                return Optional.of(Collections.unmodifiableSet(names));
            } catch (IntrospectionException | LinkageError | RuntimeException e) {
                LoggerFactory.getLogger(SearchParameterCheck.class)
                        .warn("Cannot introspect SearchCriteria, using the jahia-impl 8.2.3.2 property list", e);
                return Optional.empty();
            }
        }
    }

    /** The {@code SearchCriteria} property names a {@code src_} parameter can set. */
    static Set<String> criteriaProperties() {
        return CriteriaProperties.NAMES;
    }

    static boolean isIntrospected() {
        return CriteriaProperties.INTROSPECTED.isPresent();
    }

    static boolean isSearchParameter(String name) {
        return name != null && name.startsWith(SEARCH_PARAMETER_PREFIX);
    }

    /**
     * @return the first parameter through which a potential search reaches beyond the hostname's site
     *         and systemsite, or null
     */
    static ParameterViolation findViolation(Map<String, String[]> parameters, Set<String> sites, String siteKeyFromHost) {
        if (!isPotentialSearch(parameters)) {
            return null;
        }
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            final String name = parameter.getKey();
            if (isSearchParameter(name)) {
                final String target = scopeViolation(name, elements(parameter.getValue()), sites, siteKeyFromHost);
                if (target != null) {
                    return new ParameterViolation(name, target);
                }
            }
        }
        if (!isScoped(parameters)) {
            return new ParameterViolation(SEARCH_PARAMETER_PREFIX + "*", "every site (no site or path scope)");
        }
        return null;
    }

    /** A non-blank value on a real {@code SearchCriteria} property; no criterion list to keep up to date. */
    private static boolean isPotentialSearch(Map<String, String[]> parameters) {
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            final String name = parameter.getKey();
            if (isSearchParameter(name) && criteriaProperties().contains(property(name))
                    && parameter.getValue() != null
                    && Arrays.stream(parameter.getValue()).anyMatch(StringUtils::isNotBlank)) {
                return true;
            }
        }
        return false;
    }

    /** What one parameter's elements reach beyond the hostname's site and systemsite, or null. */
    private static String scopeViolation(String name, List<String> elements, Set<String> sites, String siteKeyFromHost) {
        final String property = property(name);
        final boolean isSiteList = SITE_LIST_PROPERTIES.contains(property);
        if (!isSiteList && !isPathParameter(name, property)) {
            return null;
        }
        if (hasNonBlank(elements) && elements.stream().anyMatch(SearchParameterCheck::isBlankElement)) {
            // Kept next to a non-blank one: '/sites/' + '' for a site, the repository root for a path.
            return "every site (blank element)";
        }
        for (String element : elements) {
            final String target = isSiteList ? siteViolation(element, sites, siteKeyFromHost)
                    : pathViolation(element, sites, siteKeyFromHost);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /**
     * Some scope property is set by every parameter that names it, so whichever BeanUtils applies last
     * leaves a scope on the query. A path is only the scope when non-empty, else the provider falls back.
     */
    private static boolean isScoped(Map<String, String[]> parameters) {
        for (String scopeProperty : PATH_PROPERTIES) {
            if (isAlwaysSet(parameters, scopeProperty)) {
                return true;
            }
        }
        return isAlwaysSet(parameters, SITES_PROPERTY);
    }

    private static boolean isAlwaysSet(Map<String, String[]> parameters, String scopeProperty) {
        boolean isNamed = false;
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            final String name = parameter.getKey();
            if (isSearchParameter(name) && scopeProperty.equals(property(name)) && !name.endsWith(INCLUDE_CHILDREN_SUFFIX)) {
                if (!hasNonBlank(elements(parameter.getValue()))) {
                    return false;
                }
                isNamed = true;
            }
        }
        return isNamed;
    }

    private static boolean isPathParameter(String name, String property) {
        return PATH_PROPERTIES.contains(property) && !name.endsWith(INCLUDE_CHILDREN_SUFFIX);
    }

    /** The bean property a BeanUtils expression starts with: "sites" for "src_sites.values[0]". */
    private static String property(String name) {
        final String expression = name.substring(SEARCH_PARAMETER_PREFIX.length());
        final int end = StringUtils.indexOfAny(expression, ".[(");
        return end < 0 ? expression : expression.substring(0, end);
    }

    /**
     * The values Jahia ends up with: a repeated parameter keeps each value as it is; a single value is
     * split at commas ({@code CommaSeparatedMultipleValue#setValue}, {@code ArrayConverter}).
     */
    private static List<String> elements(String[] values) {
        if (values == null || values.length == 0) {
            return Collections.emptyList();
        }
        if (values.length > 1) {
            return Arrays.asList(values);
        }
        return Arrays.asList(StringUtils.split(StringUtils.defaultString(values[0]), ','));
    }

    private static boolean isBlankElement(String element) {
        return element == null || WRAPPING_CHARACTERS.matcher(element).replaceAll("").isEmpty();
    }

    private static boolean hasNonBlank(List<String> elements) {
        return elements.stream().anyMatch(e -> !isBlankElement(e));
    }

    private static String siteViolation(String element, Set<String> sites, String siteKeyFromHost) {
        final List<String> candidates = new ArrayList<>(Arrays.asList(NON_KEY_CHARACTERS.split(element)));
        candidates.add(element.trim());
        for (String token : candidates) {
            if (EVERY_SITE_TOKENS.contains(token)) {
                return "every site ('" + token + "')";
            }
            if (isForeignSite(token, sites, siteKeyFromHost)) {
                return "site '" + token + "'";
            }
        }
        return null;
    }

    /**
     * A search path is confined when it lies under {@code /sites/<key>} for a key that is not another
     * existing site. Empty segments are dropped and HTML entities decoded as the XPath branch of the
     * provider does; the JCR {@code {}} marker and {@code [n]} index are removed per segment. A
     * relative path or one outside {@code /sites/<key>} is refused.
     */
    private static String pathViolation(String rawPath, Set<String> sites, String siteKeyFromHost) {
        if (isBlankElement(rawPath)) {
            return null;
        }
        final String path = StringEscapeUtils.unescapeHtml(rawPath.trim());
        final List<String> segments = new ArrayList<>();
        for (String segment : StringUtils.split(path, '/')) {
            final String name = SAME_NAME_SIBLING_INDEX.matcher(StringUtils.removeStart(segment, "{}")).replaceFirst("");
            if (".".equals(name) || "..".equals(name)) {
                return "every site (path '" + path + "')";
            }
            segments.add(name);
        }
        if (!path.startsWith("/") || segments.size() < 2 || !CrossSitesFilter.SITES_NODE_NAME.equals(segments.get(0))) {
            return "every site (path '" + path + "')";
        }
        final String siteKey = segments.get(1);
        return isForeignSite(siteKey, sites, siteKeyFromHost) ? "site '" + siteKey + "'" : null;
    }

    private static boolean isForeignSite(String siteKey, Set<String> sites, String siteKeyFromHost) {
        return sites.contains(siteKey) && !siteKey.equals(siteKeyFromHost)
                && !JahiaSitesService.SYSTEM_SITE_KEY.equals(siteKey);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    /** The parameter that selected a site the hostname does not map to, and what it selected. */
    static final class ParameterViolation {
        final String parameter;
        final String target;

        ParameterViolation(String parameter, String target) {
            this.parameter = parameter;
            this.target = target;
        }
    }
}
