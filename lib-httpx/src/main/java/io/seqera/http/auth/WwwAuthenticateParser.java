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

package io.seqera.http.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for WWW-Authenticate header values according to RFC 7235.
 * 
 * <p>This parser extracts authentication challenges from WWW-Authenticate headers,
 * focusing on Basic and Bearer authentication schemes. It handles both single
 * and multiple challenges within the same header value.
 * 
 * <p>Supported challenge formats:
 * <pre>
 * Basic realm="Protected Area"
 * Bearer realm="api", scope="read write"
 * Basic realm="apps", Bearer realm="api"
 * </pre>
 * 
 * <p>The parser is designed to be forgiving with whitespace and robust against
 * malformed headers, logging warnings for unsupported or invalid challenges
 * while continuing to process valid ones.
 * 
 * <p>This class is thread-safe and stateless.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see AuthenticationChallenge
 * @see AuthenticationScheme
 */
public class WwwAuthenticateParser {
    
    private static final Logger log = LoggerFactory.getLogger(WwwAuthenticateParser.class);
    
    // Pattern to match authentication challenges: scheme followed by optional parameters
    private static final Pattern CHALLENGE_PATTERN = Pattern.compile(
        "([a-zA-Z][a-zA-Z0-9\\-_]*)" +  // scheme name
        "(?:\\s+(.*?))?$",               // optional parameters (everything else)
        Pattern.CASE_INSENSITIVE
    );
    
    // Pattern to match auth parameters: name=value or name="quoted-value"
    private static final Pattern PARAM_PATTERN = Pattern.compile(
        "([a-zA-Z][a-zA-Z0-9\\-_]*)" +  // parameter name
        "\\s*=\\s*" +                   // equals with optional whitespace
        "(?:\"([^\"]*)\"|([^,\\s]*))",  // quoted value or unquoted value
        Pattern.CASE_INSENSITIVE
    );
    
    /**
     * Parses a WWW-Authenticate header value and returns a list of authentication challenges.
     * 
     * <p>This method handles multiple challenges separated by scheme boundaries, each with their own
     * authentication scheme and parameters. Only Basic and Bearer schemes are supported;
     * other schemes are logged at DEBUG level and ignored.
     * 
     * <p><strong>Parsing Behavior:</strong>
     * <ul>
     *   <li>Multiple challenges are separated by scheme boundaries, not just commas</li>
     *   <li>Parameter values can be quoted ("value") or unquoted (value)</li>
     *   <li>Whitespace around parameters and values is automatically trimmed</li>
     *   <li>Unsupported schemes are logged at DEBUG level and skipped</li>
     *   <li>Malformed challenges are logged at WARN level and skipped</li>
     *   <li>Empty or null header values return an empty list</li>
     * </ul>
     * 
     * <p><strong>Supported Challenge Examples:</strong>
     * <pre>
     * "Basic realm=\"Protected Area\""
     * "Bearer realm=\"api\", scope=\"read write\""
     * "Basic realm=\"apps\", Bearer realm=\"api\", scope=\"user:read\""
     * "Digest realm=\"digest\", Basic realm=\"basic\""  // Only Basic will be parsed
     * </pre>
     * 
     * <p><strong>RFC Compliance:</strong><br>
     * This parser follows RFC 7235 (HTTP/1.1 Authentication) for WWW-Authenticate
     * header format, with practical extensions for handling real-world header variations
     * from different server implementations.
     * 
     * <p><strong>Performance:</strong><br>
     * The parser uses compiled regex patterns for efficient parsing and is designed
     * to handle typical authentication headers without performance overhead.
     * 
     * @param headerValue the WWW-Authenticate header value to parse. May be null or empty.
     * @return a list of parsed authentication challenges. Never null, but may be empty
     *         if no supported challenges are found or if the header value is malformed.
     */
    public static List<AuthenticationChallenge> parse(String headerValue) {
        List<AuthenticationChallenge> challenges = new ArrayList<>();
        
        if (headerValue == null || headerValue.trim().isEmpty()) {
            log.trace("WWW-Authenticate header value is null or empty");
            return challenges;
        }
        
        log.trace("Parsing WWW-Authenticate header: {}", headerValue);
        
        // Split the header by commas, but be careful with quoted values
        String[] challengeTokens = splitChallenges(headerValue);
        
        for (String challengeToken : challengeTokens) {
            AuthenticationChallenge challenge = parseChallenge(challengeToken.trim());
            if (challenge != null) {
                challenges.add(challenge);
            }
        }
        
        log.trace("Parsed {} authentication challenges", challenges.size());
        return challenges;
    }
    
    /**
     * Splits the header value into individual challenge tokens, handling quoted values properly.
     * 
     * <p>This implementation properly handles multiple challenges by looking for authentication
     * scheme names to identify challenge boundaries, rather than simply splitting on commas
     * which could be within parameter values.
     * 
     * @param headerValue the complete header value
     * @return array of challenge tokens
     */
    private static String[] splitChallenges(String headerValue) {
        List<String> challenges = new ArrayList<>();
        
        // Use a more sophisticated approach that looks for scheme names
        Pattern schemePattern = Pattern.compile("\\b(Basic|Bearer|Digest|OAuth)\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = schemePattern.matcher(headerValue);
        
        List<Integer> schemePositions = new ArrayList<>();
        while (matcher.find()) {
            schemePositions.add(matcher.start());
        }
        
        // Extract challenges based on scheme positions
        for (int i = 0; i < schemePositions.size(); i++) {
            int start = schemePositions.get(i);
            int end = (i + 1 < schemePositions.size()) ? schemePositions.get(i + 1) : headerValue.length();
            
            String challenge = headerValue.substring(start, end).trim();
            // Remove trailing comma and whitespace
            challenge = challenge.replaceAll(",\\s*$", "").trim();
            
            if (!challenge.isEmpty()) {
                challenges.add(challenge);
            }
        }
        
        // If no scheme patterns found, treat the whole thing as one challenge
        if (challenges.isEmpty() && !headerValue.trim().isEmpty()) {
            challenges.add(headerValue.trim());
        }
        
        return challenges.toArray(new String[0]);
    }
    
    /**
     * Parses a single authentication challenge from a token string.
     * 
     * @param challengeToken the challenge token to parse
     * @return the parsed AuthenticationChallenge, or null if parsing failed or scheme is unsupported
     */
    private static AuthenticationChallenge parseChallenge(String challengeToken) {
        if (challengeToken.isEmpty()) {
            return null;
        }
        
        Matcher challengeMatcher = CHALLENGE_PATTERN.matcher(challengeToken);
        if (!challengeMatcher.matches()) {
            log.warn("Invalid challenge format: {}", challengeToken);
            return null;
        }
        
        String schemeName = challengeMatcher.group(1);
        String parametersString = challengeMatcher.group(2);
        
        // Check if we support this authentication scheme
        AuthenticationScheme scheme = AuthenticationScheme.fromString(schemeName);
        if (scheme == null) {
            log.debug("Unsupported authentication scheme '{}', skipping challenge", schemeName);
            return null;
        }
        
        // Parse parameters if present
        Map<String, String> parameters = new HashMap<>();
        if (parametersString != null && !parametersString.trim().isEmpty()) {
            parseParameters(parametersString.trim(), parameters);
        }
        
        log.trace("Parsed challenge: scheme={}, parameters={}", scheme, parameters);
        return new AuthenticationChallenge(scheme, parameters);
    }
    
    /**
     * Parses the parameters portion of a challenge into a map.
     * 
     * @param parametersString the parameters string (e.g., 'realm="test", scope="read"')
     * @param parameters the map to populate with parsed parameters
     */
    private static void parseParameters(String parametersString, Map<String, String> parameters) {
        Matcher paramMatcher = PARAM_PATTERN.matcher(parametersString);
        
        while (paramMatcher.find()) {
            String name = paramMatcher.group(1);
            String quotedValue = paramMatcher.group(2);  // Value from quoted string
            String unquotedValue = paramMatcher.group(3);  // Value from unquoted string
            
            String value = quotedValue != null ? quotedValue : unquotedValue;
            if (value != null) {
                parameters.put(name, value);
                log.trace("Parsed parameter: {}={}", name, value);
            }
        }
    }
}
