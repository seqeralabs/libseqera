/*
 * Copyright 2024, Seqera Labs
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

import groovy.text.GStringTemplateEngine
import groovy.transform.CompileStatic

/**
 * Helper functions to handle mail notification
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
final class MailHelper {

    private MailHelper() {}

    static String getTemplateFile(String classpathResource, Map binding) {
        def source = MailHelper.getResourceAsStream(classpathResource)
        if (!source)
            throw new IllegalArgumentException("Cannot load notification default template -- check classpath resource: $classpathResource")
        loadMailTemplate0(source, binding)
    }

    static private String loadMailTemplate0(InputStream source, Map binding) {
        def map = new HashMap()
        map.putAll(binding)

        def template = new GStringTemplateEngine().createTemplate(new InputStreamReader(source))
        template.make(map).toString()
    }

}
