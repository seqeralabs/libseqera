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
