/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.util

import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TemplateRendererTest extends Specification {

    def 'should replace vars' () {
        given:
        def binding = [foo: 'Hello', bar: 'world']
        def render = new TemplateRenderer()
        expect:
        render.replace0('{{foo}}', binding) == 'Hello'
        render.replace0('{{foo}} ', binding) == 'Hello '
        render.replace0('{{foo}}\n', binding) == 'Hello\n'
        render.replace0('  {{foo}}', binding) == '  Hello'
        render.replace0('  {{foo}}\n', binding) == '  Hello\n'
        render.replace0('  ${foo}', binding)  == '  ${foo}'
        render.replace0('  ${{foo}}', binding) == '  ${{foo}}'
        render.replace0('{{foo}}', [foo:'']) == ''
        render.replace0('{{foo}}', [foo:null]) == null
        render.replace0('  {{foo}}\n', [foo:null]) == null
        render.replace0('', binding) == ''
        render.replace0(null, binding) == null

        render.replace0('{{foo}} {{bar}}!', binding) == 'Hello world!'
        render.replace0('abc {{foo}} pq {{bar}} xyz', binding) == 'abc Hello pq world xyz'
        render.replace0('{{foo}} 123 {{bar}} xyz {{foo}}', binding) == 'Hello 123 world xyz Hello'
        render.replace0('1{{foo}}2{{foo}}3', [foo:'']) == '123'
        render.replace0('1{{foo}}2{{foo}}3', [foo:null]) == '123'
    }

    def 'should throw an exception when missing variables' () {
        when:
        new TemplateRenderer().replace0('{{x1}}', [:])
        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'Missing template key: x1'

        when:
        new TemplateRenderer().replace0('{{foo}} {{x2}}', [foo:'ciao'])
        then:
        e = thrown(IllegalArgumentException)
        e.message == 'Missing template key: x2'
    }

    def 'should not throw an exception when missing variables' () {
        when:
        def result = new TemplateRenderer().withIgnore("x1").replace0('{{x1}}', [x1:'one'])
        then:
        result == '{{x1}}'

        when:
        result = new TemplateRenderer().withIgnore('x1','x2').replace0('{{x1}} {{x2}}', [x1:'one'])
        then:
        result == '{{x1}} {{x2}}'
    }

    def 'should render template' () {
        given:
        def template = "Hello, {{name}}!\n" +
                "Today is {{day}} and the weather is {{weather}}.";
        and:
        def binding = new HashMap<String,String>();
        binding.put("name", "John");
        binding.put("day", "Monday");
        binding.put("weather", "sunny");

        when:
        def renderer = new TemplateRenderer()
        and:
        def result = renderer.render(template, binding);

        then:
        result == 'Hello, John!\nToday is Monday and the weather is sunny.'
    }

    def 'should render a template with comment'() {
        given:
        def template = """\
            ## remove this comment
            1: {{alpha}}
            2: {{delta}} {{delta}}
            3: {{gamma}} {{gamma}} {{gamma}} 
            4: end
            """.stripIndent()
        and:
        def binding = new HashMap<String,String>();
        binding.put("alpha", "one");
        binding.put("delta", "two");
        binding.put("gamma", "three");

        when:
        def renderer = new TemplateRenderer()
        and:
        def result = renderer.render(new ByteArrayInputStream(template.bytes), binding);

        then:
        result == """\
            1: one
            2: two two
            3: three three three 
            4: end
            """.stripIndent()
    }


    def 'should render a template using an input stream'() {
        given:
        def template = """\
            {{one}}
            {{two}}
            xxx
            {{three}}            
            zzz
            """.stripIndent()
        and:
        def binding = [
                one: '1',   // this is rendered
                two:null,   // a line containing a null variable is not rendered
                three:''    // empty value is considered ok
        ]

        when:
        def renderer = new TemplateRenderer()
        and:
        def result = renderer.render(new ByteArrayInputStream(template.bytes), binding);

        then:
        result == """\
            1
            xxx
            
            zzz
            """.stripIndent()
    }

    def 'should render template with indentations' () {
        given:
        def binding = [foo: 'Hello', bar: 'world']

        when:
        def renderer = new TemplateRenderer()
        and:
        def result = renderer.render('{{foo}}\n{{bar}}', binding)
        then:
        result == 'Hello\nworld'

        when:
        def template = '''\
              {{foo}}
            {{bar}}
            '''.stripIndent()
        result = renderer.render(template, [foo:'11\n22\n33', bar:'Hello world'])
        then:
        result == '''\
              11
              22
              33
            Hello world
            '''.stripIndent()


        when:
        template = '''\
            {{x1}}
            {{x2}}
            {{x3}}
            '''.stripIndent()
        result = renderer.render(template, [x1:'aa\nbb\n', x2:null, x3:'pp\nqq'])
        then:
        result == '''\
            aa
            bb
            
            pp
            qq
            '''.stripIndent()

    }

}
