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

package io.seqera.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable container for JWT authentication credentials.
 *
 * <p>This class holds a JWT access token and an optional refresh token as a pair.
 * The storage key can be computed using {@link #key(HxAuth)} which returns the
 * SHA-256 hash of the access token.
 *
 * <p>Instances are created using the factory methods {@link #of(String)} or
 * {@link #of(String, String)}.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public final class HxAuth {

    private final String accessToken;
    private final String refreshToken;

    private HxAuth(String accessToken, String refreshToken) {
        if (accessToken == null) {
            throw new IllegalArgumentException("accessToken cannot be null");
        }
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    /**
     * Creates an HxAuth instance.
     *
     * @param token the authentication token
     * @return a new HxAuth instance with null refresh token
     */
    public static HxAuth of(String token) {
        return new HxAuth(token, null);
    }

    /**
     * Creates an HxAuth instance.
     *
     * @param token the authentication token
     * @param refresh the refresh token
     * @return a new HxAuth instance
     */
    public static HxAuth of(String token, String refresh) {
        return new HxAuth(token, refresh);
    }

    /**
     * Computes the storage key for the given authentication object.
     *
     * <p>Returns the SHA-256 hash of the access token as a hexadecimal string.
     *
     * @param auth the authentication object (must not be null)
     * @return the computed key as a 64-character hexadecimal string
     * @throws IllegalArgumentException if auth is null
     */
    public static String key(HxAuth auth) {
        if (auth == null) {
            throw new IllegalArgumentException("auth cannot be null");
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(auth.accessToken().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes the storage key for the given authentication object, or returns a default value if null.
     *
     * @param auth the authentication object, may be null
     * @param defaultValue the value to return if auth is null
     * @return the computed key, or defaultValue if auth is null
     */
    public static String keyOrDefault(HxAuth auth, String defaultValue) {
        return auth != null ? key(auth) : defaultValue;
    }

    /**
     * Returns the JWT access token.
     *
     * @return the access token
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * Returns the refresh token.
     *
     * @return the refresh token, or null if not set
     */
    public String refreshToken() {
        return refreshToken;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HxAuth hxAuth = (HxAuth) o;
        return Objects.equals(accessToken, hxAuth.accessToken)
                && Objects.equals(refreshToken, hxAuth.refreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessToken, refreshToken);
    }

    @Override
    public String toString() {
        return "HxAuth[" +
                "accessToken=" + mask(accessToken) +
                ", refreshToken=" + mask(refreshToken) +
                ']';
    }

    private static String mask(String value) {
        if (value == null) return "null";
        if (value.length() <= 8) return "***";
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
