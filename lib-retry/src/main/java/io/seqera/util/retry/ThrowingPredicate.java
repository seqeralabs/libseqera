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

package io.seqera.util.retry;

/**
 * A {@link java.util.function.Predicate}-like functional interface whose body is allowed
 * to throw any {@link Throwable}, including checked exceptions.
 *
 * <p>This type exists so {@link Retryable} (and its consumers) can offer
 * throwing-predicate support in their public APIs without leaking the type system of
 * the underlying retry engine. Keeping the engine an internal implementation detail means
 * callers depend only on {@code io.seqera.util.retry} types and remain insulated from
 * future engine upgrades or replacements.
 *
 * <p>The contract intentionally mirrors a permissive checked-throwing predicate
 * ({@code throws Throwable}) so anything expressible against the underlying engine is
 * also expressible against this type.
 *
 * @param <T> the type of the input to the predicate
 */
@FunctionalInterface
public interface ThrowingPredicate<T> {

    /**
     * Evaluates this predicate on the given argument.
     *
     * @param t the input argument
     * @return {@code true} if the input matches the predicate, otherwise {@code false}
     * @throws Throwable if the predicate body fails
     */
    boolean test(T t) throws Throwable;
}
