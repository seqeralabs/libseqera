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

package io.seqera.lang.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Helper class to handle Java types and reflection operations.
 *
 * <p>This utility class provides methods for working with Java's type system,
 * particularly for extracting generic type information at runtime using reflection.
 * This is commonly needed when implementing generic base classes that need to know
 * their type parameters.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * public abstract class Base<T> {
 *     private final Type type;
 *
 *     protected Base() {
 *         this.type = TypeHelper.getGenericType(this, 0);
 *     }
 * }
 *
 * public class Derived extends Base<String> {
 *     // type will be String.class
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class TypeHelper {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private TypeHelper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Retrieves the generic type parameter at the specified index from an object's class hierarchy.
     *
     * <p>This method uses reflection to extract the actual type arguments from a parameterized
     * superclass. It's particularly useful for retrieving type information in generic base classes
     * where the type parameter is specified by subclasses.</p>
     *
     * <p>The method assumes the object's class directly extends a parameterized type. If the
     * superclass is not parameterized or the index is out of bounds, this method may throw
     * runtime exceptions.</p>
     *
     * @param object the object whose generic superclass type parameter should be extracted
     * @param index the zero-based index of the type parameter to retrieve
     * @return the Type at the specified index in the generic superclass's type parameters
     * @throws ClassCastException if the generic superclass is not a ParameterizedType
     * @throws ArrayIndexOutOfBoundsException if the index is invalid
     */
    public static Type getGenericType(Object object, int index) {
        final ParameterizedType params = (ParameterizedType) (object.getClass().getGenericSuperclass());
        return params.getActualTypeArguments()[index];
    }
}
