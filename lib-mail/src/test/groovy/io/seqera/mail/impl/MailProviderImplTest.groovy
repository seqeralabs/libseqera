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


import spock.lang.Specification

import javax.mail.internet.MimeMessage

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject

@MicronautTest
class MailProviderImplTest extends Specification {

    @Inject
    MailProviderImpl mailProvider

    def "should throw IllegalArgumentException"() {

        given:
        MimeMessage mimeMessage = Mock(MimeMessage)
        io.seqera.mail.Mailer mailer = Mock(io.seqera.mail.Mailer)

        when:
        mailProvider.send(mimeMessage, mailer)

        then:
        thrown(IllegalArgumentException)
    }
}
