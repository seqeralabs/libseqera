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

package io.seqera.util.net

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests for SsrfValidator utility class
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
class SsrfValidatorTest extends Specification {

    @Unroll
    def 'should reject private IP addresses: #ip'() {
        when:
        SsrfValidator.validateHost(ip)

        then:
        def e = thrown(SsrfValidationException)
        e.message.contains('Invalid registry hostname') || e.message.contains('localhost')

        where:
        ip << [
            '10.0.0.1',
            '10.255.255.255',
            '172.16.0.1',
            '172.31.255.255',
            '192.168.1.1',
            '192.168.255.255',
            '127.0.0.1',
            '127.0.0.2',
            '169.254.169.254',  // AWS metadata service
            '0.0.0.0'
        ]
    }

    @Unroll
    def 'should reject localhost variations: #host'() {
        when:
        SsrfValidator.validateHost(host)

        then:
        def e = thrown(SsrfValidationException)
        e.message.contains('localhost') || e.message.contains('Invalid registry hostname')

        where:
        host << [
            'localhost',
            'LOCALHOST',
            'localhost.localdomain'
        ]
    }

    @Unroll
    def 'should accept public hostnames: #host'() {
        when:
        SsrfValidator.validateHost(host)

        then:
        noExceptionThrown()

        where:
        host << [
            'docker.io',
            'registry-1.docker.io',
            'quay.io',
            'ghcr.io',
            'gcr.io',
            'public.ecr.aws',
            'example.com',
            'github.com'
        ]
    }

    def 'should reject null or empty inputs'() {
        when:
        SsrfValidator.validateHost(null)

        then:
        thrown(SsrfValidationException)

        when:
        SsrfValidator.validateHost('')

        then:
        thrown(SsrfValidationException)
    }

    def 'should reject cloud metadata service IPs'() {
        when:
        SsrfValidator.validateHost('169.254.169.254')

        then:
        def e = thrown(SsrfValidationException)
        e.message.contains('Invalid registry hostname')
    }

    @Unroll
    def 'should strip port and reject private host:port inputs: #hostPort'() {
        when:
        SsrfValidator.validateHost(hostPort)

        then:
        thrown(SsrfValidationException)

        where:
        hostPort << [
            '192.168.1.1:5000',
            '10.0.0.1:8080',
            '127.0.0.1:5000',
        ]
    }

    @Unroll
    def 'should reject IPv6 loopback and private addresses: #host'() {
        when:
        SsrfValidator.validateHost(host)

        then:
        thrown(SsrfValidationException)

        where:
        host << [
            '::1',
            '0000:0000:0000:0000:0000:0000:0000:0001',
            '[::1]:5000',
        ]
    }

    @Unroll
    def 'should extract hostname from URL and accept public registries: #url'() {
        when:
        SsrfValidator.validateHost(url)

        then:
        noExceptionThrown()

        where:
        url << [
            'https://registry-1.docker.io',
            'https://quay.io',
            'https://ghcr.io',
            'http://registry-1.docker.io',
            'https://gcr.io/some/path',
        ]
    }

    @Unroll
    def 'should extract hostname from URL and reject private hosts: #url'() {
        when:
        SsrfValidator.validateHost(url)

        then:
        thrown(SsrfValidationException)

        where:
        url << [
            'http://localhost:8080',
            'https://localhost/v2',
            'http://127.0.0.1:5000',
            'http://10.0.0.1:8080',
            'http://192.168.1.1:5000',
            'https://169.254.169.254/latest/meta-data',
        ]
    }
}
