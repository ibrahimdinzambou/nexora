package com.iptv.saas.service;

import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationIntegrationTests {
    @Test
    void sendsSmtpTestWithConfiguredSender() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        TransactionalMailService mail = new TransactionalMailService(
                sender,
                "smtp.example.test",
                587,
                "mailer@example.test",
                "noreply@example.test",
                "Nexora",
                true,
                true,
                false
        );

        TransactionalMailService.DeliveryResult result = mail.test("admin@example.test");

        assertTrue(result.success(), result.message());
        assertEquals("Test SMTP Nexora", message.getSubject());
        assertEquals("admin@example.test", message.getAllRecipients()[0].toString());
        verify(sender).send(message);
    }

    @Test
    void sendsHtmlAsMultipartAlternative() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        TransactionalMailService mail = new TransactionalMailService(
                sender,
                "smtp.example.test",
                587,
                "mailer@example.test",
                "noreply@example.test",
                "Nexora",
                true,
                true,
                false
        );

        TransactionalMailService.DeliveryResult result = mail.deliverHtml(
                "admin@example.test",
                "Modele HTML",
                "<html><body><strong>Bonjour</strong></body></html>"
        );

        assertTrue(result.success(), result.message());
        assertTrue(message.getContent() instanceof Multipart);
        verify(sender).send(message);
    }

    @Test
    void reportsTelegramAsDisabledWithoutExposingSecrets() {
        TelegramAlertService telegram = new TelegramAlertService(false, "secret-token", "-100123456", new ObjectMapper());

        TelegramAlertService.DeliveryResult result = telegram.test();

        assertFalse(result.success());
        assertFalse((Boolean) telegram.status().get("configured"));
        assertEquals("***3456", telegram.status().get("chatId"));
        assertEquals("***configure***", telegram.status().get("botToken"));
    }
}
