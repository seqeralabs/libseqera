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
package io.seqera.data.command;

import java.lang.reflect.Type;

import io.seqera.lang.type.TypeHelper;

/**
 * Registration info for a command handler, including resolved generic types.
 *
 * @param handler    The command handler
 * @param paramsType The resolved params type (P)
 * @param resultType The resolved result type (R)
 * @param <P>        The type of command parameters
 * @param <R>        The type of command result
 */
public record CommandRegistration<P, R>(
        CommandHandler<P, R> handler,
        Class<P> paramsType,
        Class<R> resultType
) {

    /**
     * Create a command registration by extracting generic types via reflection.
     *
     * @param handler The command handler
     * @param <P>     The type of command parameters
     * @param <R>     The type of command result
     * @return The command registration with resolved types
     */
    @SuppressWarnings("unchecked")
    public static <P, R> CommandRegistration<P, R> of(CommandHandler<P, R> handler) {
        Type[] typeArgs = TypeHelper.getInterfaceTypeArguments(handler.getClass(), CommandHandler.class);
        if (typeArgs == null || typeArgs.length < 2) {
            throw new IllegalArgumentException(
                    "Cannot resolve generic types for handler: " + handler.getClass().getName() +
                            ". Ensure the handler directly implements CommandHandler<P, R> with concrete types."
            );
        }

        Class<P> paramsType = (Class<P>) TypeHelper.getRawType(typeArgs[0]);
        Class<R> resultType = (Class<R>) TypeHelper.getRawType(typeArgs[1]);

        return new CommandRegistration<>(handler, paramsType, resultType);
    }

    /**
     * Get the command type handled by this registration.
     */
    public String type() {
        return handler.type();
    }
}
