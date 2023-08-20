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
