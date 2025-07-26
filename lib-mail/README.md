# lib-mail

Email functionality with Micronaut integration for Seqera platform components.

## Usage

Send emails with attachments and HTML content:

```groovy
@Configuration
static class MailConfig {
    @Bean
    @Primary 
    MailerConfig mailerConfig() {
        return MailerConfig.builder()
            .smtp(host: 'smtp.gmail.com', port: 587, auth: true, starttls: true)
            .auth(username: 'user@gmail.com', password: 'password')
            .build()
    }
}

@Inject
Mailer mailer

def mail = Mail.builder()
    .to('recipient@example.com')
    .subject('Test Email')
    .body('<h1>Hello World!</h1>', 'text/html')
    .attachment(new MailAttachment('report.pdf', pdfContent))
    .build()

mailer.send(mail)
```

## Testing

```bash
./gradlew :lib-mail:test
```