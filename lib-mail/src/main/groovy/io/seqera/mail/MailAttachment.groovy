/*
 * Copyright 2023, Seqera Labs
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

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * Model a mail attachment
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EqualsAndHashCode
@CompileStatic
class MailAttachment implements Serializable {

    public static final Map ATTACH_HEADERS = [
            contentId: String,
            disposition:String,
            fileName: String,
            description: String
    ]

    /**
     * The attachment file
     */
    File file

    /**
     * The attachment as classpath resource
     */
    String resource

    String contentId

    String description

    String disposition

    String fileName

    static MailAttachment resource(Map params, String path) {
//        final res = this.class.getResource(path)
//        if( !res ) throw new IllegalArgumentException("Unable to load resource from classpath: $path")
        final result = new MailAttachment(params)
        result.@resource = path
        return result
    }

    protected MailAttachment() {}

    MailAttachment(attach ) {
        this([:], attach)
    }

    MailAttachment(Map params ) {
        if( params.contentId )
            this.contentId = params.contentId

        if( params.description )
            this.description = params.description

        if( params.disposition )
            this.disposition = params.disposition

        if( params.fileName )
            this.fileName = params.fileName
    }

    MailAttachment(Map params, attach ) {
        this(params)

        if( attach instanceof File ) {
            this.file = attach
        }
        else if( attach instanceof Path ) {
            this.file = attach.toFile()
        }
        else if( attach instanceof String || attach instanceof GString ) {
            this.file = new File(attach.toString())
        }
        else if( attach != null )
            throw new IllegalArgumentException("Invalid attachment argument: $attach [${attach.getClass()}]")

    }

    File getFile() { file }

    String getResource() { resource }

    String getFileName() {
        if( fileName )
            return fileName

        if( file )
            return file.getName()

        if( resource ) {
            def str = resource
            def p = str.indexOf(':')
            if( p!=-1 )
                str = resource.substring(p+1)
            p = str.lastIndexOf('/')
            if( p!=-1 )
                str = str.substring(p+1)
            return str
        }

        return null
    }
}
