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
 */
package io.seqera.lib.hash;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SipHash-2-4 based implementation of {@link Hasher}.
 * SipHash is a fast, cryptographically strong PRF designed for hash table lookups.
 * Uses 2 compression rounds per block and 4 finalization rounds.
 *
 * @author Paolo Di Tommaso
 * @see <a href="https://131002.net/siphash/">SipHash specification</a>
 */
public class Sip24Hasher implements Hasher {

    // Default key (can be any 128-bit value for non-security use cases)
    private static final long K0 = 0x736f6d6570736575L;
    private static final long K1 = 0x646f72616e646f6dL;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public Hasher putString(String value) {
        if (value != null) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buffer.write(bytes, 0, bytes.length);
        }
        return this;
    }

    @Override
    public Hasher putBoolean(boolean value) {
        buffer.write(value ? 1 : 0);
        return this;
    }

    @Override
    public Hasher putInt(int value) {
        // Write as little-endian to match SipHash expectations
        buffer.write(value & 0xff);
        buffer.write((value >> 8) & 0xff);
        buffer.write((value >> 16) & 0xff);
        buffer.write((value >> 24) & 0xff);
        return this;
    }

    @Override
    public Hasher putSeparator() {
        buffer.write(0);
        return this;
    }

    @Override
    public long toLong() {
        return sipHash24(K0, K1, buffer.toByteArray());
    }

    /**
     * Compute SipHash-2-4 for the given data.
     *
     * @param k0 first 64 bits of the 128-bit key
     * @param k1 second 64 bits of the 128-bit key
     * @param data the input data
     * @return the 64-bit hash
     */
    private static long sipHash24(long k0, long k1, byte[] data) {
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;

        int len = data.length;
        int blocks = len / 8;

        // Process 8-byte blocks
        for (int i = 0; i < blocks; i++) {
            long m = bytesToLong(data, i * 8);
            v3 ^= m;
            // 2 compression rounds
            long[] state = sipRound2(v0, v1, v2, v3);
            v0 = state[0];
            v1 = state[1];
            v2 = state[2];
            v3 = state[3];
            v0 ^= m;
        }

        // Process remaining bytes with length encoding
        long last = ((long) len) << 56;
        int remaining = len % 8;
        int offset = blocks * 8;

        switch (remaining) {
            case 7: last |= ((long) (data[offset + 6] & 0xff)) << 48;
            case 6: last |= ((long) (data[offset + 5] & 0xff)) << 40;
            case 5: last |= ((long) (data[offset + 4] & 0xff)) << 32;
            case 4: last |= ((long) (data[offset + 3] & 0xff)) << 24;
            case 3: last |= ((long) (data[offset + 2] & 0xff)) << 16;
            case 2: last |= ((long) (data[offset + 1] & 0xff)) << 8;
            case 1: last |= ((long) (data[offset] & 0xff));
            case 0: break;
        }

        v3 ^= last;
        long[] state = sipRound2(v0, v1, v2, v3);
        v0 = state[0];
        v1 = state[1];
        v2 = state[2];
        v3 = state[3];
        v0 ^= last;

        // Finalization (4 rounds)
        v2 ^= 0xff;
        state = sipRound4(v0, v1, v2, v3);
        v0 = state[0];
        v1 = state[1];
        v2 = state[2];
        v3 = state[3];

        return v0 ^ v1 ^ v2 ^ v3;
    }

    /**
     * Perform 2 SipHash rounds (for compression).
     */
    private static long[] sipRound2(long v0, long v1, long v2, long v3) {
        // Round 1
        v0 += v1;
        v2 += v3;
        v1 = Long.rotateLeft(v1, 13);
        v3 = Long.rotateLeft(v3, 16);
        v1 ^= v0;
        v3 ^= v2;
        v0 = Long.rotateLeft(v0, 32);
        v2 += v1;
        v0 += v3;
        v1 = Long.rotateLeft(v1, 17);
        v3 = Long.rotateLeft(v3, 21);
        v1 ^= v2;
        v3 ^= v0;
        v2 = Long.rotateLeft(v2, 32);

        // Round 2
        v0 += v1;
        v2 += v3;
        v1 = Long.rotateLeft(v1, 13);
        v3 = Long.rotateLeft(v3, 16);
        v1 ^= v0;
        v3 ^= v2;
        v0 = Long.rotateLeft(v0, 32);
        v2 += v1;
        v0 += v3;
        v1 = Long.rotateLeft(v1, 17);
        v3 = Long.rotateLeft(v3, 21);
        v1 ^= v2;
        v3 ^= v0;
        v2 = Long.rotateLeft(v2, 32);

        return new long[]{v0, v1, v2, v3};
    }

    /**
     * Perform 4 SipHash rounds (for finalization).
     */
    private static long[] sipRound4(long v0, long v1, long v2, long v3) {
        long[] state = sipRound2(v0, v1, v2, v3);
        return sipRound2(state[0], state[1], state[2], state[3]);
    }

    /**
     * Read 8 bytes as a little-endian long.
     */
    private static long bytesToLong(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff)) |
               ((long) (data[offset + 1] & 0xff) << 8) |
               ((long) (data[offset + 2] & 0xff) << 16) |
               ((long) (data[offset + 3] & 0xff) << 24) |
               ((long) (data[offset + 4] & 0xff) << 32) |
               ((long) (data[offset + 5] & 0xff) << 40) |
               ((long) (data[offset + 6] & 0xff) << 48) |
               ((long) (data[offset + 7] & 0xff) << 56);
    }
}
