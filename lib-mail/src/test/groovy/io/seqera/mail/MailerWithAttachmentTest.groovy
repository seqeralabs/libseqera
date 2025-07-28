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

import java.nio.file.Files
import java.nio.file.Path
import javax.mail.Message
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.mail.impl.MailProviderImpl
import org.subethamail.wiser.Wiser
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
class MailerWithAttachmentTest extends Specification {


    void "should send email with attachment"() {
        given:
        Integer PORT = 3025
        String USER = 'foo'
        String PASSWORD = 'secret'
        Wiser server = new Wiser(PORT)
        server.start()

        MailerConfig config = new MailerConfig(smtp:[host: '127.0.0.1', port: PORT, user: USER, password: PASSWORD])
        MailProvider provider = new MailProviderImpl()
        Mailer mailer = new Mailer(config: config, provider: provider)

        String TO = "receiver@gmail.com"
        String FROM = 'paolo@nextflow.io'
        String SUBJECT = "Sending test"
        String CONTENT = "This content should be sent by the user."
        Path ATTACH = Files.createTempFile('test', null)
        ATTACH.toFile().text = 'This is the file attachment content'

        when:
        Map mail = [
                from: FROM,
                to: TO,
                subject: SUBJECT,
                body: CONTENT,
                attach: ATTACH
        ]
        mailer.send(mail)

        then:
        server.messages.size() == 1
        Message message = server.messages.first().mimeMessage
        message.from == [new InternetAddress(FROM)]
        message.allRecipients.contains(new InternetAddress(TO))
        message.subject == SUBJECT
        (message.content as MimeMultipart).count == 2

        cleanup:
        if( ATTACH ) Files.delete(ATTACH)
        server?.stop()
    }
}
