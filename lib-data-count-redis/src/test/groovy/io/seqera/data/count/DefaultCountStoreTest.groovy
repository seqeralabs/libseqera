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

package io.seqera.data.count

import io.micronaut.context.ApplicationContext
import io.micronaut.inject.qualifiers.Qualifiers
import spock.lang.Shared
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class DefaultCountStoreTest extends Specification {

    @Shared
    ApplicationContext applicationContext

    def setup() {
        applicationContext = ApplicationContext.run([
                'seqera.count.tasks.prefix': 'task-counter',
                'seqera.count.builds': [:]
        ], 'test')
    }

    def cleanup() {
        applicationContext.close()
    }

    def 'should create bean for each config entry' () {
        when:
        def tasks = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('tasks'))
        def builds = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('builds'))

        then:
        tasks != null
        and:
        builds != null
    }

    def 'should use configured prefix' () {
        given:
        def store = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('tasks'))

        expect:
        store.increment('x') == 1
        and:
        store.increment('x') == 2
        and:
        store.increment('x', 5) == 7
        and:
        store.get('x') == 7
    }

    def 'should default prefix to config name' () {
        given:
        def store = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('builds'))

        expect:
        store.increment('x') == 1
        and:
        store.get('x') == 1
    }

    def 'should isolate stores by prefix' () {
        given:
        def tasks = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('tasks'))
        def builds = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('builds'))

        when:
        tasks.increment('x', 10)
        builds.increment('x', 20)

        then:
        tasks.get('x') == 10
        and:
        builds.get('x') == 20
    }

    def 'should clear counter' () {
        given:
        def store = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('tasks'))

        when:
        store.increment('z', 10)
        and:
        store.clear('z')

        then:
        store.get('z') == 0
    }

    def 'should decrement' () {
        given:
        def store = applicationContext.getBean(DefaultCountStore, Qualifiers.byName('tasks'))

        expect:
        store.decrement('y') == -1
        and:
        store.decrement('y', 4) == -5
        and:
        store.get('y') == -5
    }
}
