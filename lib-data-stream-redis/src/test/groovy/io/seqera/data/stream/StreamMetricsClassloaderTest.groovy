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

package io.seqera.data.stream

import spock.lang.Specification

/**
 * Verifies the classloader-safety claim of the lib-data-stream-redis metrics design:
 * with micrometer-core excluded from the classpath, AbstractMessageStream and a
 * concrete subclass must still load and be instantiable via the 1-arg constructor
 * (which delegates to the 2-arg ctor with null) without {@link NoClassDefFoundError}.
 *
 * The strategy:
 *  - Build an isolated URLClassLoader from the current test classpath minus any
 *    jar whose filename starts with {@code micrometer-core}.
 *  - Use {@link ClassLoader#getPlatformClassLoader()} as the parent so JDK classes
 *    still resolve but application classes can only be found through the explicit
 *    URL list (preventing any unintended leakage from the system classloader).
 *  - Load LocalMessageStream + TestPlainStream through this loader and instantiate
 *    the subclass via the 1-arg constructor.
 *
 * If the JVM eagerly resolved {@code MeterRegistry} when verifying the 2-arg
 * constructor's descriptor, this test would fail with NoClassDefFoundError on a
 * given JDK — that is the regression we are guarding against.
 *
 * @author Paolo Di Tommaso
 */
class StreamMetricsClassloaderTest extends Specification {

    private URL[] classpathWithoutMicrometer() {
        return System.getProperty('java.class.path')
                .split(File.pathSeparator)
                .findAll { entry ->
                    // exclude micrometer-core jars; keep everything else (including
                    // logback, Groovy runtime, project classes/jars).
                    def file = new File(entry).name
                    !file.startsWith('micrometer-core')
                }
                .collect { entry -> new File(entry).toURI().toURL() }
                .toArray(new URL[0])
    }

    def 'sanity check — Micrometer is excluded from the isolated classloader'() {
        given:
        def isolated = new URLClassLoader(classpathWithoutMicrometer(), ClassLoader.platformClassLoader)

        when:
        Class.forName('io.micrometer.core.instrument.MeterRegistry', false, isolated)

        then:
        thrown(ClassNotFoundException)

        cleanup:
        isolated?.close()
    }

    def 'AbstractMessageStream is loadable and usable without micrometer-core'() {
        given:
        def isolated = new URLClassLoader(classpathWithoutMicrometer(), ClassLoader.platformClassLoader)

        when: 'classes load through the isolated loader'
        Class<?> localCls         = Class.forName('io.seqera.data.stream.impl.LocalMessageStream', true, isolated)
        Class<?> messageStreamIfc = Class.forName('io.seqera.data.stream.MessageStream',           true, isolated)
        Class<?> subclassCls      = Class.forName('io.seqera.data.stream.TestPlainStream',         true, isolated)
        Class<?> abstractCls      = Class.forName('io.seqera.data.stream.AbstractMessageStream',   true, isolated)

        then: 'every class came from the isolated loader, not the parent'
        localCls.classLoader.is(isolated)
        subclassCls.classLoader.is(isolated)
        abstractCls.classLoader.is(isolated)

        when: 'instantiate the subclass via its 1-arg constructor (which calls super(target, null))'
        def target   = localCls.getDeclaredConstructor().newInstance()
        def instance = subclassCls.getDeclaredConstructor(messageStreamIfc).newInstance(target)

        then: 'no NoClassDefFoundError / LinkageError thrown'
        noExceptionThrown()
        instance != null

        when: 'close() — exercises an instance method end-to-end'
        instance.close()

        then:
        noExceptionThrown()

        cleanup:
        isolated?.close()
    }
}
