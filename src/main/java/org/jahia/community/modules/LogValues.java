package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;

import java.util.regex.Pattern;

/**
 * Makes attacker-chosen values safe to log. SLF4J does not escape placeholder arguments, so the host,
 * path, referer, parameter names and values go through {@link #sanitise} and {@link #abbreviate}.
 */
final class LogValues {

    /** ASCII and C1 controls (incl. NEL U+0085), Unicode format characters (bidi overrides, zero-width) and the line and paragraph separators. */
    private static final Pattern LOG_UNSAFE_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}\\u2028\\u2029]");

    private static final int MAX_LOGGED_LENGTH = 512;

    private LogValues() {
    }

    static String sanitise(String message) {
        return LOG_UNSAFE_CHARS.matcher(StringUtils.defaultString(message)).replaceAll("?");
    }

    /** Only a bounded prefix goes to the log; a surrogate pair is never cut. */
    static String abbreviate(String text) {
        if (text.length() <= MAX_LOGGED_LENGTH) {
            return text;
        }
        final int cut = Character.isHighSurrogate(text.charAt(MAX_LOGGED_LENGTH - 1)) ? MAX_LOGGED_LENGTH - 1 : MAX_LOGGED_LENGTH;
        return text.substring(0, cut) + "…";
    }

    /** Sanitised and capped. */
    static String safe(String text) {
        return sanitise(abbreviate(StringUtils.defaultString(text)));
    }

    /** The page embedding a cross-site link, so an admin can find it; "-" when the browser sent none. */
    static String describeReferer(String referer) {
        return StringUtils.isEmpty(referer) ? "-" : safe(referer);
    }
}
