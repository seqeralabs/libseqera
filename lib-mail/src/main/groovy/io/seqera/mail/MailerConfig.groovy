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

package io.seqera.mail

import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.convert.format.MapFormat

/**
 * Model mailer config object
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@ConfigurationProperties('mail')
@CompileDynamic
class MailerConfig {

    String to

    String from

    @MapFormat(transformation = MapFormat.MapTransformation.FLAT)
    Map<String,?> smtp = [auth: false]

    Map<String,?> transport = Collections.emptyMap()

    boolean debug

    Properties getMailProperties() {
        def props = new Properties()
        for( Map.Entry<String,?> entry : smtp ) {
            props.setProperty('mail.smtp.' + entry.key, entry.value?.toString() )
        }

        props.setProperty('mail.transport.protocol', transport.protocol  ?: 'smtp')

        // -- debug for debugging
        if( debug ) {
            log.debug "Mail session properties:\n${props.dump()}"
        }
        else
            log.trace "Mail session properties:\n${props.dump()}"

        return props
    }

    protected String sysProp(String key) {
        System.getProperty(key)
    }
}
