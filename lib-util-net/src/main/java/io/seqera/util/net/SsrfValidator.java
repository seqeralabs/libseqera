/*
 * Copyright 2026, Seqera Labs
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

package io.seqera.util.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to prevent Server-Side Request Forgery (SSRF) attacks
 * by validating hostnames before making HTTP requests.
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
public final class SsrfValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfValidator.class);

    private SsrfValidator() {}

    // Cloud metadata service IPs
    private static final Set<String> METADATA_IPS = Set.of(
        "169.254.169.254",  // AWS metadata service- https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html
        "169.254.170.2",    // AWS ECS metadata service - https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html
        "fd00:ec2::254"     // AWS IMDSv2 IPv6 - https://aws.amazon.com/blogs/aws/amazon-ec2-instance-metadata-service-imdsv2-by-default/
    );

    // Localhost variations that should be rejected before DNS resolution
    private static final Set<String> LOCALHOST_NAMES = Set.of(
        "localhost",
        "localhost.localdomain",
        "0.0.0.0",
        "0000:0000:0000:0000:0000:0000:0000:0001",
        "::1" // https://datatracker.ietf.org/doc/html/rfc4291#section-2.5.3
    );

    /**
     * Validates a hostname to ensure it doesn't resolve to internal/private resources
     *
     * @param host The hostname to validate
     * @throws SsrfValidationException if the hostname is potentially malicious
     */
    public static void validateHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new SsrfValidationException("Host cannot be null or empty");
        }

        // Normalize host (lowercase, trim)
        host = host.toLowerCase().trim();

        // Extract hostname from URL if scheme is present
        host = extractHostname(host);

        // A null host (e.g. an authority-less URL) cannot be validated - fail closed
        if (host == null) {
            throw new SsrfValidationException("Invalid registry hostname");
        }

        // Check localhost variations
        if (LOCALHOST_NAMES.contains(host)) {
            throw new SsrfValidationException("Access to localhost is not allowed: " + host);
        }

        // Resolve the host to IP address(es) and validate each
        try {
            final InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                validateIpAddress(addr);
            }
        }
        catch (UnknownHostException e) {
            // Fail closed - reject hosts that cannot be resolved
            throw new SsrfValidationException("Unable to resolve host: " + host);
        }
    }

    /**
     * Extracts the hostname from a registry string
     */
    private static String extractHostname(String host) {
        if (host.startsWith("http://") || host.startsWith("https://")) {
            try {
                return new URI(host).getHost();
            }
            catch (URISyntaxException ignored) {
                return host;
            }
        }
        // Handle bracketed IPv6 with optional port: [::1]:8080
        if (host.startsWith("[")) {
            final int closeBracket = host.indexOf(']');
            if (closeBracket > 0) {
                return host.substring(1, closeBracket);
            }
        }
        // Strip port from bare host:port (e.g. 192.168.1.1:5000)
        // Only when there is exactly one colon (not IPv6 which has multiple)
        final int colonIdx = host.lastIndexOf(':');
        if (colonIdx > 0 && host.indexOf(':') == colonIdx) {
            return host.substring(0, colonIdx);
        }
        return host;
    }

    /**
     * Validates an InetAddress to ensure it's not a private or internal address
     */
    private static void validateIpAddress(InetAddress address) {
        final String ip = address.getHostAddress();

        // Check metadata service IPs first (before link-local, for a specific error message)
        if (METADATA_IPS.contains(ip)) {
            log.debug("SSRF validation rejected cloud metadata service IP: {}", ip);
            throw new SsrfValidationException("Invalid registry hostname");
        }

        if (address.isLoopbackAddress()) {
            log.debug("SSRF validation rejected loopback address: {}", ip);
            throw new SsrfValidationException("Invalid registry hostname");
        }

        if (address.isLinkLocalAddress()) {
            log.debug("SSRF validation rejected link-local address: {}", ip);
            throw new SsrfValidationException("Invalid registry hostname");
        }

        if (address.isSiteLocalAddress()) {
            log.debug("SSRF validation rejected private IP address: {}", ip);
            throw new SsrfValidationException("Invalid registry hostname");
        }

        // Check for IPv6 unique local addresses (fc00::/7)
        if (address instanceof Inet6Address) {
            final byte[] bytes = address.getAddress();
            if ((bytes[0] & 0xfe) == 0xfc) {
                log.debug("SSRF validation rejected IPv6 unique local address: {}", ip);
                throw new SsrfValidationException("Invalid registry hostname");
            }
        }
    }
}
