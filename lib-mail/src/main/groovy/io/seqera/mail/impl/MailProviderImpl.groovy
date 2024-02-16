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

package io.seqera.mail.impl

import javax.mail.internet.MimeMessage

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.mail.Mailer
import io.seqera.mail.MailProvider
import jakarta.inject.Singleton

/**
 * Send a mime message via Java mail
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
@Slf4j
@Singleton
@CompileStatic
class MailProviderImpl implements MailProvider {

    /**
     * Send a email message by using the Java API
     *
     * @param message A {@link MimeMessage} object representing the email to send
     */
    @Override
    void send(MimeMessage message, Mailer mailer) {
        if( !message.getAllRecipients() )
            throw new IllegalArgumentException("Missing mail message recipient")

        final transport = mailer.getSession().getTransport()
        log.debug("Connecting to host=${mailer.host} port=${mailer.port} user=${mailer.user}")
        transport.connect(mailer.host, mailer.port as int, mailer.user, mailer.password)
        try {
            transport.sendMessage(message, message.getAllRecipients())
        }
        finally {
            transport.close()
        }
    }

}
