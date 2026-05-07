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

package io.seqera.cloudinfo.api

import spock.lang.Specification

import java.lang.reflect.Field
import java.lang.reflect.Modifier

class ProductsQueryTest extends Specification {

    def 'equals returns true for instances with identical field values'() {
        expect:
        ProductsQuery.builder().sched(true).nvme(true).build() ==
                ProductsQuery.builder().sched(true).nvme(true).build()
    }

    def 'equals returns false when sched differs'() {
        expect:
        ProductsQuery.builder().sched(true).build() !=
                ProductsQuery.builder().sched(false).build()
    }

    def 'equals returns false when nvme differs'() {
        expect:
        ProductsQuery.builder().nvme(true).build() !=
                ProductsQuery.builder().nvme(false).build()
    }

    def 'hashCode is consistent with equals'() {
        given:
        def a = ProductsQuery.builder().sched(true).nvme(false).build()
        def b = ProductsQuery.builder().sched(true).nvme(false).build()

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    /**
     * Safety-net guard: any declared instance field on ProductsQuery must
     * influence hashCode. If a future field is added to ProductsQuery without
     * being included in hashCode, this test fails for that field. Avoids
     * silent caching bugs in consumers that key off ProductsQuery.hashCode.
     */
    def 'every declared instance field on ProductsQuery affects hashCode'() {
        given:
        def declaredFields = ProductsQuery.getDeclaredFields()
                .findAll { Field f ->
                    !Modifier.isStatic(f.modifiers) && !f.synthetic
                }

        expect: 'at least one field exists (sanity)'
        !declaredFields.isEmpty()

        and: 'each field, when flipped from default, produces a different hashCode'
        declaredFields.each { Field f ->
            f.accessible = true
            def defaults = ProductsQuery.builder().build()
            def flipped = ProductsQuery.builder().build()
            // Mutate the flipped instance via reflection to a non-default value of the field's type
            def flippedValue = nonDefaultValueFor(f.type, f.get(defaults))
            f.set(flipped, flippedValue)
            assert defaults.hashCode() != flipped.hashCode(),
                    "Field '${f.name}' on ProductsQuery does not contribute to hashCode. " +
                    "Update ProductsQuery.hashCode (and equals) to include this field."
            assert defaults != flipped,
                    "Field '${f.name}' on ProductsQuery does not contribute to equals. " +
                    "Update ProductsQuery.equals to include this field."
        }
    }

    private static Object nonDefaultValueFor(Class<?> type, Object currentValue) {
        if (type == boolean.class || type == Boolean) {
            return !((Boolean) currentValue)
        }
        if (type == int.class || type == Integer) {
            return (currentValue == null ? 0 : (int) currentValue) + 1
        }
        if (type == long.class || type == Long) {
            return (currentValue == null ? 0L : (long) currentValue) + 1L
        }
        if (type == String) {
            return currentValue == null ? 'X' : currentValue + 'X'
        }
        // Extend as ProductsQuery grows. Fail loudly if a new type appears.
        throw new IllegalStateException(
                "ProductsQueryTest does not know how to flip a field of type ${type.name}; " +
                "extend nonDefaultValueFor() or update the test.")
    }
}
