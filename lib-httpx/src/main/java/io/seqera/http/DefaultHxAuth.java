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

import java.util.UUID;

/**
 * Default immutable implementation of {@link HxAuth}.
 *
 * <p>Stores JWT access token, refresh token, and refresh URL.
 * A stable {@link #id()} is assigned at creation and preserved
 * through {@link #withToken(String)} and {@link #withRefresh(String)}.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
record DefaultHxAuth(String id, String accessToken, String refreshToken, String refreshUrl) implements HxAuth {

    DefaultHxAuth {
        if (accessToken == null) {
            throw new IllegalArgumentException("accessToken cannot be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
    }

    DefaultHxAuth(String accessToken, String refreshToken, String refreshUrl) {
        this(UUID.randomUUID().toString(), accessToken, refreshToken, refreshUrl);
    }

    DefaultHxAuth(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, null);
    }

    @Override
    public HxAuth withToken(String token) {
        return new DefaultHxAuth(id, token, refreshToken, refreshUrl);
    }

    @Override
    public HxAuth withRefresh(String refresh) {
        return new DefaultHxAuth(id, accessToken, refresh, refreshUrl);
    }

    @Override
    public String toString() {
        return "HxAuth[" +
                "id=" + id +
                ", accessToken=" + mask(accessToken) +
                ", refreshToken=" + mask(refreshToken) +
                ", refreshUrl=" + refreshUrl +
                ']';
    }

    private static String mask(String value) {
        if (value == null) return "null";
        if (value.length() <= 8) return "***";
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
