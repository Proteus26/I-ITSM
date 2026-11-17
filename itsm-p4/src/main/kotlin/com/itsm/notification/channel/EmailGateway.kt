package com.itsm.notification.channel

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/** Adapter pattern: EmailChannel only knows this interface, not which email service is behind it. */
interface EmailGateway {
    fun send(to: String, subject: String, body: String)
}

/** Sends real SMTP. In development this points at Mailpit (see docker-compose.yml, inbox at :8025). */
class SmtpEmailGateway(
    private val sender: JavaMailSender,
    private val from: String,
) : EmailGateway {
    override fun send(to: String, subject: String, body: String) {
        val msg = SimpleMailMessage()
        msg.from = from
        msg.setTo(to)
        msg.subject = subject
        msg.text = body
        sender.send(msg)
    }
}

/** Fallback that only writes the email to the log. */
class LoggingEmailGateway : EmailGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, body: String) {
        log.info("EMAIL to={} subject=\"{}\"\n{}", to, subject, body)
    }
}

/** Picks the gateway from `itsm.mail.gateway` (smtp | logging). Logging is the default. */
@Configuration
class EmailGatewayConfig {
    @Bean
    @ConditionalOnProperty(name = ["itsm.mail.gateway"], havingValue = "smtp")
    fun smtpEmailGateway(
        sender: JavaMailSender,
        @Value("\${itsm.mail.from:itsm@localhost}") from: String,
    ): EmailGateway = SmtpEmailGateway(sender, from)

    @Bean
    @ConditionalOnProperty(name = ["itsm.mail.gateway"], havingValue = "logging", matchIfMissing = true)
    fun loggingEmailGateway(): EmailGateway = LoggingEmailGateway()
}
