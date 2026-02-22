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

package io.seqera.service.pairing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Utility class to compute MD5-based cache keys for pairing service.
 *
 * This is byte-for-byte compatible with {@code io.seqera.wave.util.DigestFunctions}
 * MD5 methods, inlined here to avoid a dependency on wave-utils.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CacheKey {

    private static MessageDigest getMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to load MD5 digest algorithm", e);
        }
    }

    public static String md5(String str) {
        if( str==null )
            str = Character.toString(0);
        final byte[] digest = getMd5().digest(str.getBytes());
        return bytesToHex(digest);
    }

    public static String md5(Map<String,Object> map) throws NoSuchAlgorithmException {
        if( map==null || map.size()==0 )
            throw new IllegalArgumentException("Cannot compute MD5 checksum for a null or empty map");
        final String result = concat0(map, new StringBuilder());
        return md5(result);
    }

    @SuppressWarnings("unchecked")
    private static String concat0(Map<String,Object> map, StringBuilder result) {
        for( Map.Entry<String,Object> entry : map.entrySet() ) {
            // compute key checksum
            result.append( entry.getKey() );
            result.append( Character.toString(0x1C) );
            // compute key checksum
            final Object value = entry.getValue();
            if( value instanceof Map ) {
                concat0( (Map<String, Object>) value, result );
            }
            else if( value instanceof Collection ) {
                final Iterator<?> itr = ((Collection<?>) value).iterator();
                int i=0;
                while(itr.hasNext()) {
                    if( i++>0 ) result.append( Character.toString(0x1D) );
                    result.append( str0(itr.next()) );
                }
            }
            else {
                result.append( str0(value) );
            }
            result.append( Character.toString(0x1E) );
        }
        return result.toString();
    }

    private static String str0(Object object) {
        return object != null ? object.toString() : Character.toString(0x0);
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        for (byte byt : bytes) result.append(Integer.toString((byt & 0xff) + 0x100, 16).substring(1));
        return result.toString();
    }
}
