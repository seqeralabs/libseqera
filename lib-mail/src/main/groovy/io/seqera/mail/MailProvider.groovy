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

package io.seqera.mail

import javax.mail.internet.MimeMessage

/**
 * Define a generic interface to send an email modelled as a Mime message object
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
interface MailProvider {

    void send(MimeMessage message, Mailer mailer)

}
