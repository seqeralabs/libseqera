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

package io.seqera.util.string

import spock.lang.Specification
import spock.lang.Unroll

class StringUtilsTest extends Specification {

    def 'isEmpty should return #expected for #value'() {
        expect:
        StringUtils.isEmpty(value) == expected
        where:
        value   | expected
        null    | true
        ''      | true
        ' '    | false
        'abc'   | false
    }

    def 'isBlank should return #expected for #value'() {
        expect:
        StringUtils.isBlank(value) == expected
        where:
        value   | expected
        null    | true
        ''      | true
        ' '    | true
        '  '   | true
        'abc'   | false
        ' a '  | false
    }

    def 'isNotEmpty should return #expected for #value'() {
        expect:
        StringUtils.isNotEmpty(value) == expected
        where:
        value   | expected
        null    | false
        ''      | false
        'abc'   | true
    }

    def 'isNotBlank should return #expected for #value'() {
        expect:
        StringUtils.isNotBlank(value) == expected
        where:
        value   | expected
        null    | false
        ''      | false
        ' '    | false
        'abc'   | true
    }

    def 'should check lower case strings' () {
        expect:
        StringUtils.isLowerCase('abc')
        !StringUtils.isLowerCase('AAA')
        StringUtils.isLowerCase('1a11a1a')
    }

    def 'should check upper case strings' () {
        expect:
        StringUtils.isUpperCase('ABCD')
        !StringUtils.isUpperCase('aaaa')
        StringUtils.isUpperCase('1A11A1A')
    }

    @Unroll
    def 'should check string like' () {
        expect:
        StringUtils.like(STR, LIKE) == EXPECT
        where:
        STR                 | LIKE          | EXPECT
        'foo'               | 'foo'         | true
        'foo'               | 'f*'          | true
        'foo'               | '*'           | true
        'foo'               | 'bar'         | false
        'foo'               | '*ar'         | false
        'paolo@gmail.com'   | '*@gmail.com' | true
        'PAOLO@gmail.com'   | '*@gmail.com' | true
        'paolo@yahoo.com'   | '*@gmail.com' | false
        'x.y_w-z@this.com'  | '*@this.com'  | true
        'x.y_w-z@THIS.com'  | '*@this.com'  | true
        'x.y_w-z@that.com'  | '*@this.com'  | false
    }

    @Unroll
    void 'transform a glob pattern to a Java regex pattern'() {
        given:
        def pattern = StringUtils.globToRegex(glob)
        when:
        boolean result = (text ==~ pattern)
        then:
        result == matches
        where:
        glob           | text              | matches
        'starts*'      | 'starts with'     | true
        'starts*'      | 'StArTs WiTh'     | true
        'starts*'      | 'not starts with' | false
        '*end'         | 'with end'        | true
        '*end'         | 'WitH EnD'        | true
        '*end'         | 'no end here'     | false
        '*contains*'   | 'it contains it'  | true
        '*contains*'   | 'It CoNtAiNs It'  | true
        '*contains*'   | 'it contains'     | true
        '*contains*'   | 'contains it'     | true
        '*contains*'   | 'no match'        | false
        'singl?'       | 'single'          | true
        'singl?'       | 'SiNgLe'          | true
        'singl?'       | 'no single'       | false
        /rege? \d+./   | 'regex 3.'        | false
        /rege? \d+./   | /regex \d+./      | true
        /comple? glo*/ | 'complex glob'    | true
    }

    @Unroll
    def 'should strip secret' () {
        expect:
        StringUtils.redact(SECRET) == EXPECTED
        where:
        SECRET          | EXPECTED
        'hi'            | '****'
        'Hello'         | '****'
        'World'         | '****'
        '1234567890'    | '123****'
        'hola'          | '****'
        null            | '(null)'
        ''              | '(empty)'
    }

    @Unroll
    def 'should strip url password' () {
        expect:
        StringUtils.redactUrlPassword(SECRET) == EXPECTED
        where:
        SECRET                  | EXPECTED
        'hi'                    | 'hi'
        'http://foo/bar'        | 'http://foo/bar'
        'http://secret@foo/bar' | 'http://****@foo/bar'
    }

    @Unroll
    def 'should strip passwords' () {
        expect:
        StringUtils.stripSecrets(SECRET as Map<String,Object>) == EXPECTED
        where:
        SECRET                                  | EXPECTED
        null                                    | null
        [foo:'Hello']                           | [foo:'Hello']
        [foo: [bar: 'World']]                   | [foo: [bar: 'World']]
        [foo: [password:'hola', token:'hi']]    | [foo: [password:'****', token:'****']]
        [foo: [password:'1234567890']]          | [foo: [password:'123****']]
        [foo: [customPassword:'hola']]          | [foo: [customPassword:'****']]
        [foo: [towerLicense:'hola']]            | [foo: [towerLicense:'****']]
        [foo: [myKey: '12345']]                 | [foo: [myKey:'****']]
        [url: 'redis://host:port']              | [url: 'redis://host:port']
        [url: 'redis://secret@host:port']       | [url: 'redis://****@host:port']
        [url: 'ftp://secretlong@host:port/x/y'] | [url: 'ftp://sec****@host:port/x/y']
    }

    @Unroll
    def 'should get url protocol' () {
        expect:
        StringUtils.getUrlProtocol(STR) == EXPECTED
        where:
        EXPECTED    | STR
        'file'      | 'file:/abc/com'
        'file'      | 'file://abc/com'
        'ftp'       | 'ftp://abc.com'
        's3'        | 's3://bucket/abc'
        null        | 'gitzabc:xyz'
        null        | '/a/bc/'
        null        | null
        null        | ''
    }

    @Unroll
    def 'should validate isUrlPath' () {
        expect:
        StringUtils.isUrlPath(STR) == EXPECTED
        where:
        EXPECTED    | STR
        false       | 'file:abc/com'
        true        | 'file:/abc/com'
        true        | 'file://abc/com'
        true        | 'ftp://abc.com'
        true        | 's3://bucket/abc'
        false       | 'abc:xyz'
        false       | '/a/bc/'
    }

    @Unroll
    def 'should find base url #STR' () {
        expect:
        StringUtils.baseUrl(STR) == BASE
        where:
        BASE                | STR
        null                | null
        'http://foo.com'    | 'http://foo.com'
        'http://foo.com'    | 'http://foo.com/abc'
        'http://foo.com'    | 'http://foo.com/abc/mskd0fs =ds0f'
        and:
        'https://foo.com'    | 'https://foo.com'
        'https://foo.com'    | 'https://foo.com/abc'
        'https://foo.com'    | 'https://foo.com/abc/mskd0fs =ds0f'
        and:
        'https://foo.com'    | 'HTTPS://FOO.COM'
        'https://foo.com'    | 'HTTPS://FOO.COM/ABC'
        'https://foo.com'    | 'HTTPS://FOO.COM/ABC/MSKD0FS =DS0F'
        and:
        'https://foo.com:80' | 'https://foo.com:80'
        'https://foo.com:80' | 'https://foo.com:80/abc'
        'https://foo.com:80' | 'https://foo.com:80/abc/mskd0fs =ds0f'
        and:
        'ftp://foo.com:80'   | 'ftp://foo.com:80'
        'ftp://foo.com:80'   | 'ftp://foo.com:80/abc'
        's3://foo.com:80'    | 's3://foo.com:80/abc'
        and:
        null                 | 'blah'
        null                 | 'http:/xyz.com'
        null                 | '1234://xyz'
        null                 | '1234://xyz.com/abc'
    }

    @Unroll
    def 'should check if URIs are the same' () {
        expect:
        StringUtils.isSameUri(URI1, URI2) == EXPECTED
        where:
        URI1                            | URI2                            | EXPECTED
        null                            | null                            | true
        null                            | 'https://example.com'           | false
        'https://example.com'           | null                            | false
        'https://example.com'           | 'https://example.com'           | true
        'https://example.com/'          | 'https://example.com'           | true
        'https://example.com'           | 'https://example.com/'          | true
        'https://example.com/'          | 'https://example.com/'          | true
        'https://example.com//'         | 'https://example.com'           | true
        'https://example.com/api'       | 'https://example.com/api/'      | true
        'https://example.com/api/'      | 'https://example.com/api'       | true
        'HTTPS://EXAMPLE.COM'           | 'https://example.com'           | true
        'https://example.com'           | 'https://other.com'             | false
        'https://example.com/api'       | 'https://example.com/other'     | false
    }

    @Unroll
    def 'should concat paths' () {
        expect:
        StringUtils.pathConcat(BASE, PATH) == EXPECTED
        where:
        BASE        | PATH          | EXPECTED
        '/'         | 'bar'         | '/bar'
        '/'         | '/bar'        | '/bar'
        '/foo'      | '/bar'        | '/bar'
        and:
        '/foo'      | 'bar'         | '/foo/bar'
        '/foo/'     | 'bar'         | '/foo/bar'
        '/foo/'     | 'bar/'        | '/foo/bar'
        '/foo//'    | 'bar/'        | '/foo/bar'
        and:
        's3://foo'  | 'bar'         | 's3://foo/bar'
    }

    @Unroll
    def 'should match same prefix'() {
        expect:
        StringUtils.hasSamePathPrefix(PATH_LIKE, PREFIX) == EXPECT
        where:
        PATH_LIKE     |  PREFIX       | EXPECT
        '/'           |  ''           | true
        '/'           |  '/'          | true
        '/foo/bar'    | '/foo/bar'    | true
        '/foo/bar'    | '/foo'        | true
        '/foo/bar/baz'| '/foo/bar'    | true
        '/foo/bar'    | '/foo/baz'    | false
        '/foo/bar'    | '/foo/ba'     | false
        's3://foo/bar'| 's3://foo'    | true
        '/foo/bar/'   | '/foo/bar'    | true
    }

    @Unroll
    def 'should check valid emails'() {
        expect:
        StringUtils.isEmail(email)
        where:
        email                                                                       | _
        'test@iana.org'                                                             | _
        'test@nominet.org.uk'                                                       | _
        'test@about.museum'                                                         | _
        'a@iana.org'                                                                | _
        'test.test@iana.org'                                                        | _
        '!#$%&`*+/=?^`{|}~@iana.org'                                                | _
        '123@iana.org'                                                              | _
        'test@123.com'                                                              | _
        'abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghiklm@iana.org' | _
        'test@mason-dixon.com'                                                      | _
        'test@c--n.com'                                                             | _
        'test@xn--hxajbheg2az3al.xn--jxalpdlp'                                      | _
        'xn--test@iana.org'                                                         | _
        'emmanuel@hibernate.org'                                                    | _
        'emma+nuel@hibernate.org'                                                   | _
        'emma=nuel@hibernate.org'                                                   | _
        '*@example.net'                                                             | _
        'fred&barny@example.com'                                                    | _
        '---@example.com'                                                           | _
        'foo-bar@example.net'                                                       | _
        'prettyandsimple@example.com'                                               | _
        'very.common@example.com'                                                   | _
        'disposable.style.email.with+symbol@example.com'                            | _
        'other.email-with-dash@example.com'                                         | _
        'x@example.com'                                                             | _
        'jon.o\'conner@example.com'                                                 | _
        'test@iana.123'                                                             | _
        'test@255.255.255.255'                                                      | _
        'xn--80ahgue5b@xn--p-8sbkgc5ag7bhce.xn--ba-lmcq'                            | _
        'nothing@xn--fken-gra.no'                                                   | _
        'example-indeed@strange-example.com'                                        | _
    }

    @Unroll
    def 'should check invalid emails'() {
        expect:
        !StringUtils.isEmail(email)
        where:
        email                                    | _
        'emmanuel.hibernate.org'                  | _
        'emma nuel@hibernate.org'                 | _
        'emma(nuel@hibernate.org'                 | _
        'emmanuel@'                               | _
        'emma@nuel@hibernate.org'                 | _
        'emma@nuel@.hibernate.org'                | _
        'Just a string'                           | _
        'string'                                  | _
        'me@'                                     | _
        '@example.com'                            | _
        'me.@example.com'                         | _
        '.me@example.com'                         | _
        'me@example..com'                         | _
        'Abc.example.com'                         | _
        'A@b@c@example.com'                       | _
        'test@org'                                | _
    }

    @Unroll
    def 'should check email'() {
        expect:
        StringUtils.isEmail(email) == isEmail
        where:
        email              | isEmail
        'good@email.com'   | true
        'good+1@email.com' | true
        'bad email'        | false
        'bad@'             | false
        '@bad'             | false
        'bad@.email.com'   | false
        'bad@email'        | false
    }

    @Unroll
    def 'should validate trusted email' () {
        expect:
        StringUtils.isTrustedEmail(TRUSTED_LIST, EMAIL) == EXPECTED
        where:
        TRUSTED_LIST                    | EMAIL             | EXPECTED
        null                            | 'me@foo.com'      | true
        []                              | 'me@foo.com'      | false
        ['*@foo.com']                   | 'me@foo.com'      | true
        ['*@foo.com']                   | 'you@bar.com'     | false
        ['me@foo.com']                  | 'me@foo.com'      | true
        ['me@foo.com']                  | 'you@foo.com'     | false
        ['*@foo.com', '*@bar.com']      | 'me@bar.com'      | true
        ['*@foo.com', '*@bar.com']      | 'me@baz.com'      | false
    }
}
