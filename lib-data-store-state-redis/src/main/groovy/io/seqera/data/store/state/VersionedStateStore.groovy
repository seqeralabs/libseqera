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

package io.seqera.data.store.state

import java.time.Duration
import java.util.regex.Pattern

import com.github.f4b6a3.tsid.TsidCreator
import groovy.transform.CompileStatic
import io.seqera.data.store.state.impl.StateProvider
import io.seqera.data.store.state.impl.VersionProvider
import io.seqera.serde.encode.StringEncodingStrategy
/**
 * A state store for values that carry their own optimistic-concurrency version
 * (see {@link VersionAware}), adding a versioned compare-and-swap write
 * ({@link #replaceIf}) to the plain {@link AbstractStateStore} contract.
 *
 * <p>The store stamps a version — a unique time-sorted identifier (TSID) — on every
 * write path and persists it as a leading {@code {"@v":N} JSON property framing the
 * serialized payload. The frame is transparent to the encoding strategy: it is stripped
 * symmetrically on read, with its version injected into the decoded value via
 * {@link VersionAware#withVersion}, so the frame is the single source of truth for the
 * version, the decoder always receives exactly the payload the encoder produced, and
 * the value type does not need to serialize its version field.
 *
 * <p>Versioned values must serialize to a JSON object, whose leading {@code @v}
 * property is reserved for the store.
 *
 * @param <V> the value type, carrying its own optimistic-concurrency version
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
abstract class VersionedStateStore<V extends VersionAware<V>> extends AbstractStateStore<V> {

    private static final Pattern FRAME_PATTERN = Pattern.compile(/^\{"@v":(\d+)([,}])/)

    private StateProvider<String,String> delegate

    private VersionProvider<String,String> versions

    VersionedStateStore(StateProvider<String,String> provider, StringEncodingStrategy<V> encodingStrategy) {
        super(provider, encodingStrategy)
        if( !(provider instanceof VersionProvider) )
            throw new IllegalArgumentException("Versioned state store requires a provider supporting versioned writes (VersionProvider) - offending type: ${provider.getClass().getName()}")
        this.delegate = provider
        this.versions = (VersionProvider<String,String>) provider
    }

    /**
     * Serialize the value framed with a leading {@code {"@v":N} version property carrying
     * a freshly stamped version (a unique time-sorted identifier). Every write path goes
     * through here, so any write invalidates every outstanding compare-and-swap witness.
     * The frame is written by the store at a fixed position — never by the encoding
     * strategy — so the versioned compare-and-swap can inspect it server-side without
     * parsing the payload; {@link #deserialize} strips it symmetrically on read.
     */
    @Override
    protected String serialize(V value) {
        final stamped = stamp(value)
        return frame(super.serialize(stamped), stamped.version())
    }

    /**
     * Symmetric inverse of {@link #serialize}: strip the leading {@code {"@v":N} version
     * frame from the stored form — the decoder receives exactly the payload the encoding
     * strategy produced on write — and inject the frame's version into the decoded value
     * via {@link VersionAware#withVersion}. The frame is the single source of truth for
     * the version: an unframed entry counts as version {@code 0}, whatever the payload
     * itself may carry, and so does a head that merely resembles a frame but carries
     * version digits no store-written frame can (they do not fit a long) — reads never
     * throw on foreign data.
     */
    @Override
    protected V deserialize(String encoded) {
        final matcher = FRAME_PATTERN.matcher(encoded)
        if( !matcher.find() )
            return super.deserialize(encoded).withVersion(0)
        final version = parseVersion(matcher.group(1))
        if( version == null )
            return super.deserialize(encoded).withVersion(0)
        final payload = matcher.group(2) == ','
                ? '{' + encoded.substring(matcher.end())
                : '{}'
        return super.deserialize(payload).withVersion(version)
    }

    /**
     * Stamp the value with a freshly generated version — a unique time-sorted
     * identifier (TSID).
     */
    private V stamp(V value) {
        return value.withVersion(TsidCreator.getTsid().toLong())
    }

    private static Long parseVersion(String digits) {
        try {
            return Long.valueOf(digits)
        }
        catch( NumberFormatException ignored ) {
            return null
        }
    }

    private static String frame(String payload, long version) {
        if( !payload || payload.charAt(0) != ('{' as char) )
            throw new IllegalStateException("VersionAware values must serialize to a JSON object - offending payload: ${payload?.take(80)}")
        final rest = payload.substring(1)
        return rest == '}' ? '{"@v":' + version + '}' : '{"@v":' + version + ',' + rest
    }

    /**
     * Replace the value associated with the specified key only if the entry has not been
     * written since the caller read it (compare-and-swap), preserving the entry's
     * remaining time-to-live.
     *
     * <p>The write witness is the value's own {@link VersionAware#version()} — the version
     * of the read the value was derived from: the replace lands only when the stored
     * version still equals it, and the value is persisted with a freshly stamped version
     * (a unique time-sorted identifier). Every write path stamps — {@link #put} included —
     * so any write invalidates every outstanding witness. The whole compare-and-swap is
     * one atomic server-side operation: the stored version is read from the
     * {@code {"@v":N} frame the store prepends to every write, so no payload is parsed
     * or shipped for the comparison and the encoding strategy is never re-invoked —
     * no byte-determinism assumption remains anywhere.
     *
     * <p>An entry written before versioning existed carries no frame, counts as version
     * {@code 0}, and is adopted by its first successful replace.
     *
     * @param key The key of the entry to be replaced
     * @param value The new value, carrying the version of the read it was derived from
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *         exist or the entry was written since the version carried by the value
     */
    boolean replaceIf(String key, V value) {
        return replaceIf0(key, value, null)
    }

    /**
     * Same as {@link #replaceIf(String, VersionAware)}, resetting the entry time-to-live
     * to the specified duration.
     *
     * @param key The key of the entry to be replaced
     * @param value The new value, carrying the version of the read it was derived from
     * @param ttl The new max time-to-live of the entry once replaced
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *         exist or the entry was written since the version carried by the value
     */
    boolean replaceIf(String key, V value, Duration ttl) {
        return replaceIf0(key, value, ttl)
    }

    private boolean replaceIf0(String key, V value, Duration ttl) {
        final long expected = value.version()
        // a single atomic server-side call: the provider compares the version carried by
        // the stored form's leading frame against the caller's read basis and swaps in
        // the new (framed) value - no read-back, no payload comparison, no second trip
        final done = ttl != null
                ? versions.replaceIf(key0(key), expected, serialize(value), ttl)
                : versions.replaceIf(key0(key), expected, serialize(value))
        if( done && value instanceof RequestIdAware ) {
            delegate.put(requestId0(((RequestIdAware) value).getRequestId()), key, ttl != null ? ttl : getDuration())
        }
        return done
    }

}
