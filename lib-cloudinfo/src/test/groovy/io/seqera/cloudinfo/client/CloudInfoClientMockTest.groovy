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

package io.seqera.cloudinfo.client

import io.seqera.cloudinfo.api.ProductsQuery
import io.seqera.http.HxClient
import spock.lang.Specification

import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Offline, deterministic tests for CloudInfoClient: they stub the HTTP layer
 * (HxClient) so the URL construction, families parsing and error handling can
 * be asserted without depending on the live cloudinfo.seqera.io deployment.
 */
class CloudInfoClientMockTest extends Specification {

    private HttpResponse<String> ok(String content) {
        Stub(HttpResponse) {
            statusCode() >> 200
            body() >> content
        }
    }

    private HttpResponse<String> withStatus(int status, String content) {
        Stub(HttpResponse) {
            statusCode() >> status
            body() >> content
        }
    }

    private CloudInfoClient clientWith(HxClient http) {
        CloudInfoClient.builder()
                .endpoint('https://cloudinfo.test')
                .httpClient(http)
                .build()
    }

    def 'getProducts serializes the features filter as a comma-separated ?features= param'() {
        given:
        def http = Mock(HxClient)
        def response = ok('{"products":[{"type":"p4d.24xlarge","family":"p4d","features":["a100","gpu","nvidia","ssd","x86"]}]}')
        URI captured = null
        http.sendAsString(_) >> { HttpRequest req -> captured = req.uri(); response }
        def client = clientWith(http)
        def query = ProductsQuery.builder().features(['gpu', 'nvidia']).build()

        when:
        def products = client.getProducts('amazon', 'us-east-1', query)

        then:
        captured.toString() ==
                'https://cloudinfo.test/api/v1/providers/amazon/services/compute/regions/us-east-1/products?features=gpu,nvidia'
        products.size() == 1
        products[0].type == 'p4d.24xlarge'
        products[0].family == 'p4d'
        products[0].features == ['a100', 'gpu', 'nvidia', 'ssd', 'x86']
    }

    def 'getProducts serializes the families filter and preserves the dot in instance-type names'() {
        given:
        def http = Mock(HxClient)
        URI captured = null
        http.sendAsString(_) >> { HttpRequest req -> captured = req.uri(); ok('{"products":[]}') }
        def client = clientWith(http)
        def query = ProductsQuery.builder().families(['m5d', 'c5.large']).build()

        when:
        client.getProducts('amazon', 'us-east-1', query)

        then:
        captured.toString().endsWith('/products?families=m5d,c5.large')
    }

    def 'getProducts combines sched, nvme, features and families into one query string'() {
        given:
        def http = Mock(HxClient)
        URI captured = null
        http.sendAsString(_) >> { HttpRequest req -> captured = req.uri(); ok('{"products":[]}') }
        def client = clientWith(http)
        def query = ProductsQuery.builder()
                .sched(true)
                .nvme(true)
                .features(['gpu'])
                .families(['p4d'])
                .build()

        when:
        client.getProducts('amazon', 'us-east-1', query)

        then:
        def q = captured.toString()
        q.contains('/products?sched=true&nvme=true&features=gpu&families=p4d')
    }

    def 'getProducts with no query emits no query string'() {
        given:
        def http = Mock(HxClient)
        URI captured = null
        http.sendAsString(_) >> { HttpRequest req -> captured = req.uri(); ok('{"products":[]}') }
        def client = clientWith(http)

        when:
        client.getProducts('amazon', 'us-east-1')

        then:
        captured.toString().endsWith('/products')
        !captured.toString().contains('?')
    }

    def 'getFamilies without features hits the families endpoint with no query string'() {
        given:
        def http = Mock(HxClient)
        URI captured = null
        http.sendAsString(_) >> { HttpRequest req -> captured = req.uri(); ok('{"families":["a1","c5","m5d"]}') }
        def client = clientWith(http)

        when:
        def families = client.getFamilies('amazon')

        then:
        captured.toString() == 'https://cloudinfo.test/api/v1/providers/amazon/families'
        families == ['a1', 'c5', 'm5d']
    }

    def 'getFamilies with features serializes ?features= and parses the response'() {
        given:
        def http = Mock(HxClient)
        URI captured = null
        http.sendAsString(_) >> { HttpRequest req -> captured = req.uri(); ok('{"families":["g4dn","p4d"]}') }
        def client = clientWith(http)

        when:
        def families = client.getFamilies('amazon', ['gpu', 'nvidia'])

        then:
        captured.toString() == 'https://cloudinfo.test/api/v1/providers/amazon/families?features=gpu,nvidia'
        families == ['g4dn', 'p4d']
    }

    def 'getFamilies surfaces a 400 as CloudInfoException carrying validCapabilities'() {
        given:
        def http = Mock(HxClient)
        def body = '{"error":"unknown feature \\"bogus\\"","validCapabilities":["arm","gpu","ssd"]}'
        http.sendAsString(_) >> withStatus(400, body)
        def client = clientWith(http)

        when:
        client.getFamilies('amazon', ['bogus'])

        then:
        def e = thrown(CloudInfoException)
        e.statusCode == 400
        e.validCapabilities == ['arm', 'gpu', 'ssd']
        e.message.contains('bogus')
    }

    def 'getFamilies returns an empty list when the response has no families'() {
        given:
        def http = Mock(HxClient)
        http.sendAsString(_) >> ok('{}')
        def client = clientWith(http)

        expect:
        client.getFamilies('google') == []
    }
}
