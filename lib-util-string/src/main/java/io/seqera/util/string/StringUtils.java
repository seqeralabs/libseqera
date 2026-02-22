/*
 * Copyright 2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.seqera.util.string;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * String utility methods shared across Seqera projects.
 *
 * @author Paolo Di Tommaso
 */
public final class StringUtils {

    private StringUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // -- Patterns --

    private static final Pattern URL_PROTOCOL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9]*)://(.+)");
    private static final Pattern URL_PASSWORD = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*://(.+)@.+");
    private static final Pattern BASE_URL = Pattern.compile("(?i)((?:[a-z][a-zA-Z0-9]*)?://[^:|/]+(?::\\d+)?)(?:$|/.*)");
    private static final Pattern STAR_PATTERN = Pattern.compile("[^*]+|(\\*)");

    /**
     * Angular/WHATWG email validation regex.
     *
     * @see <a href="https://angular.io/api/forms/Validators#email">Angular Validators</a>
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^(?=.{1,254}$)(?=.{1,64}@)[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"
    );

    // -- Emptiness checks --

    /**
     * Checks if a string is null or empty.
     *
     * @param value the string to check
     * @return true if the string is null or empty
     */
    public static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace.
     *
     * @param value the string to check
     * @return true if the string is null, empty, or blank
     */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Checks if a string is not null and not empty.
     *
     * @param value the string to check
     * @return true if the string is not null and not empty
     */
    public static boolean isNotEmpty(String value) {
        return !isEmpty(value);
    }

    /**
     * Checks if a string is not null, not empty, and not blank.
     *
     * @param value the string to check
     * @return true if the string is not null, not empty, and not blank
     */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    // -- Case checks --

    /**
     * Check if all alphabetic characters in a string are lowercase.
     * Non-alphabetic characters are ignored.
     *
     * @param self the string to check
     * @return true if the string contains no uppercase characters
     */
    public static boolean isLowerCase(String self) {
        if (self != null) {
            for (int i = 0; i < self.length(); i++) {
                if (Character.isUpperCase(self.charAt(i)))
                    return false;
            }
        }
        return true;
    }

    /**
     * Check if all alphabetic characters in a string are uppercase.
     * Non-alphabetic characters are ignored.
     *
     * @param self the string to check
     * @return true if the string contains no lowercase characters
     */
    public static boolean isUpperCase(String self) {
        if (self != null) {
            for (int i = 0; i < self.length(); i++) {
                if (Character.isLowerCase(self.charAt(i)))
                    return false;
            }
        }
        return true;
    }

    // -- Pattern matching --

    /**
     * Glob-like pattern matching with case-insensitive wildcards (*).
     *
     * @param self the string to match
     * @param pattern the glob pattern (supports * wildcard)
     * @return true if the string matches the pattern
     */
    public static boolean like(String self, String pattern) {
        return Pattern.compile(escapeGlob(pattern), Pattern.CASE_INSENSITIVE).matcher(self).matches();
    }

    /**
     * Transforms a basic glob pattern (containing '*' or '?' characters) to a Java regex pattern.
     *
     * @param glob the basic glob pattern to transform
     * @return the equivalent Java regex pattern (case-insensitive)
     */
    public static Pattern globToRegex(String glob) {
        return Pattern.compile(
                "\\Q" + glob.replace("\\E", "\\E\\\\E\\Q")
                        .replace("*", "\\E.*\\Q")
                        .replace("?", "\\E.\\Q") + "\\E",
                Pattern.CASE_INSENSITIVE
        );
    }

    // -- Redaction --

    /**
     * Redacts a value for safe logging. Shows first 3 chars + "****" for strings
     * of 10+ chars, or just "****" for shorter strings.
     *
     * @param value the value to redact
     * @return the redacted string
     */
    public static String redact(Object value) {
        if (value == null)
            return "(null)";
        final String str = value.toString();
        if (str.isEmpty())
            return "(empty)";
        return str.length() >= 10 ? str.substring(0, 3) + "****" : "****";
    }

    /**
     * Redacts password/credentials embedded in a URL string.
     *
     * @param value the value possibly containing a URL with credentials
     * @return the string with credentials redacted
     */
    public static String redactUrlPassword(Object value) {
        final String str = value.toString();
        final Matcher m = URL_PASSWORD.matcher(str);
        if (m.matches()) {
            return new StringBuilder(str)
                    .replace(m.start(1), m.end(1), redact(m.group(1)))
                    .toString();
        }
        return str;
    }

    /**
     * Recursively redacts sensitive values in a map based on key names.
     * Keys containing "password", "token", "secret", "key", or "license"
     * (case-insensitive) are considered sensitive.
     *
     * @param map the map to strip secrets from
     * @return a new map with sensitive values redacted
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> stripSecrets(Map<String, Object> map) {
        if (map == null)
            return null;
        final Map<String, Object> copy = new HashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map) {
                copy.put(entry.getKey(), stripSecrets((Map<String, Object>) entry.getValue()));
            } else if (isSensitive(entry.getKey())) {
                copy.put(entry.getKey(), redact(entry.getValue()));
            } else {
                copy.put(entry.getKey(), redactUrlPassword(entry.getValue()));
            }
        }
        return copy;
    }

    // -- URL utilities --

    /**
     * Extracts the URL protocol from a string.
     *
     * @param str the string to extract protocol from
     * @return the protocol (e.g., "http", "s3"), or null if not a URL
     */
    public static String getUrlProtocol(String str) {
        if (str == null || str.isEmpty())
            return null;
        // file pseudo-protocol can use a single slash
        if (str.startsWith("file:/"))
            return "file";
        final Matcher m = URL_PROTOCOL.matcher(str);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Checks if a string is a URL path (has a protocol).
     *
     * @param str the string to check
     * @return true if the string has a URL protocol
     */
    public static boolean isUrlPath(String str) {
        return getUrlProtocol(str) != null;
    }

    /**
     * Extracts the base URL including protocol and host (case-insensitive).
     *
     * @param url the URL string
     * @return the base URL, or null if not a valid URL
     */
    public static String baseUrl(String url) {
        if (url == null || url.isEmpty())
            return null;
        final Matcher m = BASE_URL.matcher(url);
        return m.matches() ? m.group(1).toLowerCase() : null;
    }

    /**
     * Compares two URIs for equality, ignoring trailing slashes and case.
     *
     * @param uri1 the first URI
     * @param uri2 the second URI
     * @return true if the URIs are equivalent
     */
    public static boolean isSameUri(String uri1, String uri2) {
        if (uri1 == null && uri2 == null)
            return true;
        if (uri1 == null || uri2 == null)
            return false;
        return stripEnd(uri1, '/').equalsIgnoreCase(stripEnd(uri2, '/'));
    }

    // -- Path utilities --

    /**
     * Concatenates a base path and a relative path, handling trailing slashes
     * correctly for both local and URL paths.
     *
     * @param base the base path
     * @param path the path to append
     * @return the concatenated path
     */
    public static String pathConcat(String base, String path) {
        if (base == null || base.isEmpty())
            throw new IllegalArgumentException("Argument 'base' cannot be null or empty");
        if (path == null || path.isEmpty())
            throw new IllegalArgumentException("Argument 'path' cannot be null or empty");

        final String scheme = getUrlProtocol(base);
        final String prefix = scheme != null ? scheme + "://" : null;
        String result = base;
        while (result.endsWith("/")) {
            if (result.equals("/"))
                break;
            if (prefix != null && result.equals(prefix))
                break;
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty())
            throw new IllegalArgumentException("Invalid base path - offending value: '" + base + "'");

        final String suffix = stripEnd(path, '/');
        if (suffix.startsWith("/"))
            return suffix;
        if (suffix.isEmpty())
            return result;

        if (!result.endsWith("/"))
            result += "/";
        return result + suffix;
    }

    /**
     * Checks if two path-like strings have the same prefix when split on '/'.
     *
     * @param pathLike the string to match
     * @param pathPrefix the prefix to search
     * @return true if pathLike has pathPrefix as a path prefix
     */
    public static boolean hasSamePathPrefix(String pathLike, String pathPrefix) {
        final List<String> prefixSegments = tokenize(pathPrefix, '/');
        final List<String> pathSegments = tokenize(pathLike, '/');

        if (prefixSegments.size() > pathSegments.size())
            return false;

        for (int i = 0; i < prefixSegments.size(); i++) {
            if (!prefixSegments.get(i).equals(pathSegments.get(i)))
                return false;
        }
        return true;
    }

    // -- Email validation --

    /**
     * Validates an email address using Angular/WHATWG rules.
     *
     * @param str the string to validate
     * @return true if the string is a valid email
     */
    public static boolean isEmail(String str) {
        if (str == null || str.isEmpty())
            return false;

        final int atIdx = str.indexOf('@');
        // Must contain exactly one '@'
        if (atIdx == -1 || str.indexOf('@', atIdx + 1) != -1)
            return false;

        // TLD-only domain is disallowed
        final String domain = str.substring(atIdx + 1);
        if (!domain.contains("."))
            return false;

        return EMAIL_PATTERN.matcher(str).matches();
    }

    /**
     * Checks if the given email is trusted based on an allowed list.
     * If the allowed list is null, all emails are trusted.
     * Patterns support wildcards using * (e.g., *@domain.com).
     *
     * @param trustedEmails list of trusted email patterns
     * @param email the email to check
     * @return true if the email is trusted
     */
    public static boolean isTrustedEmail(List<String> trustedEmails, String email) {
        if (trustedEmails == null)
            return true;

        for (String pattern : trustedEmails) {
            if (like(email, pattern))
                return true;
        }
        return false;
    }

    // -- Private helpers --

    private static boolean isSensitive(Object key) {
        final String str = key.toString().toLowerCase();
        return str.contains("password")
                || str.contains("token")
                || str.contains("secret")
                || str.contains("key")
                || str.contains("license");
    }

    private static String escapeGlob(String str) {
        final Matcher m = STAR_PATTERN.matcher(str);
        final StringBuilder b = new StringBuilder();
        while (m.find()) {
            if (m.group(1) != null)
                m.appendReplacement(b, ".*");
            else
                m.appendReplacement(b, "\\\\Q" + m.group(0) + "\\\\E");
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String stripEnd(String str, char ch) {
        int end = str.length();
        while (end > 0 && str.charAt(end - 1) == ch) {
            end--;
        }
        return end < str.length() ? str.substring(0, end) : str;
    }

    private static List<String> tokenize(String str, char separator) {
        if (str == null || str.isEmpty())
            return Collections.emptyList();
        final List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == separator) {
                if (i > start)
                    result.add(str.substring(start, i));
                start = i + 1;
            }
        }
        if (start < str.length())
            result.add(str.substring(start));
        return result;
    }
}
