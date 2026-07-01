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
package io.seqera.data.command

import io.micronaut.context.ApplicationContext
import io.micronaut.inject.qualifiers.Qualifiers
import io.seqera.lock.LockManager
import io.seqera.lock.local.LocalLockManager
import spock.lang.Specification

/**
 * Guards the command-lock DI wiring: exactly one {@code @Named(COMMAND_LOCK)} {@link LockManager}
 * must resolve (no ambiguity with the {@code @EachBean(LockConfig)} managers), and the command
 * service must inject it. Docker-independent (local, non-Redis) so wiring is proven even without
 * the integration tests.
 */
class CommandLockWiringTest extends Specification {

    def 'resolves exactly one command lock manager (local mode) and injects the service'() {
        given:
        def ctx = ApplicationContext.run('test')

        when:
        def beans = ctx.getBeansOfType(LockManager, Qualifiers.byName(CommandLockFactory.COMMAND_LOCK))

        then:
        beans.size() == 1
        beans.first() instanceof LocalLockManager

        and: 'the command service resolves (its @Named lock injection is unambiguous)'
        ctx.getBean(CommandService) != null

        cleanup:
        ctx?.close()
    }
}
