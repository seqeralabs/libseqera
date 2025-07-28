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

import spock.lang.Specification

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.context.annotation.Property
import jakarta.inject.Inject
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
@Property(name = "mail.from", value = "me@google.com")
@Property(name = "mail.smtp.host", value = "localhost")
@Property(name = "mail.smtp.port", value = "3025")
@Property(name = "mail.smtp.user", value = "mr-bean")
@Property(name = "mail.smtp.password", value = "super-secret")
@Property(name = "mail.smtp.auth", value = "true")
@Property(name = "mail.smtp.starttls.enable", value = "false")
@Property(name = "mail.smtp.starttls.required", value = "false")
@Property(name = "mail.smtp.proxy.host", value = "proxy.com")
@Property(name = "mail.smtp.proxy.port", value = "5566")
class MailerConfigTest extends Specification {

    @Inject
    MailerConfig mailerConfig

    void 'should create smtp config' () {
        expect:
        mailerConfig.from == 'me@google.com'
        mailerConfig.smtp.host == 'localhost'
        mailerConfig.smtp.user == 'mr-bean'
        mailerConfig.smtp.password == 'super-secret'
        mailerConfig.smtp.port == '3025'
        mailerConfig.smtp.auth == 'true'
        mailerConfig.smtp.'starttls.enable' == 'false'
        mailerConfig.smtp.'starttls.required' == 'false'
    }


    void 'should get mail properties object' () {
        when:
        def props = mailerConfig.mailProperties

        then:
        props.'mail.smtp.host' == 'localhost'
        props.'mail.smtp.user' == 'mr-bean'
        props.'mail.smtp.password' == 'super-secret'
        props.'mail.smtp.port' == '3025'
        props.'mail.smtp.auth' == 'true'
        props.'mail.smtp.starttls.enable' == 'false'
        props.'mail.smtp.starttls.required' == 'false'
        props.'mail.smtp.proxy.host' == 'proxy.com'
        props.'mail.smtp.proxy.port' == '5566'
        props.'mail.transport.protocol' == 'smtp'
    }

}


