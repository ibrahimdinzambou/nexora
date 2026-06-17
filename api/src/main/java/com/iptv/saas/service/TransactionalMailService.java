package com.iptv.saas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TransactionalMailService {
    private static final Logger log = LoggerFactory.getLogger(TransactionalMailService.class);

    private final JavaMailSender mailSender;
    private final String host;
    private final int port;
    private final String username;
    private final String fromAddress;
    private final String fromName;
    private final boolean authentication;
    private final boolean startTls;
    private final boolean ssl;

    public TransactionalMailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:25}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${app.mail.from-address:noreply@nexora.local}") String fromAddress,
            @Value("${app.mail.from-name:Nexora}") String fromName,
            @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean authentication,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean startTls,
            @Value("${spring.mail.properties.mail.smtp.ssl.enable:false}") boolean ssl
    ) {
        this.mailSender = mailSender;
        this.host = host;
        this.port = port;
        this.username = username;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.authentication = authentication;
        this.startTls = startTls;
        this.ssl = ssl;
    }

    public void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            sendNow(to, subject, body, null, null, null);
        } catch (Exception exception) {
            log.info("Email not sent to {}: {}", to, exception.getMessage());
        }
    }

    public DeliveryResult test(String to) {
        if (to == null || to.isBlank()) {
            return new DeliveryResult(false, "Adresse de destination requise");
        }
        try {
            sendNow(to, "Test SMTP Nexora", "La connexion SMTP Nexora fonctionne correctement.", null, null, null);
            return new DeliveryResult(true, "E-mail de test envoye a " + to);
        } catch (Exception exception) {
            log.info("SMTP test failed: {}", exception.getMessage());
            return new DeliveryResult(false, rootMessage(exception));
        }
    }

    public void sendHtml(String to, String subject, String html) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            sendNow(to, subject, plainText(html), html, null, null);
        } catch (Exception exception) {
            log.info("HTML email not sent to {}: {}", to, exception.getMessage());
        }
    }

    public void sendHtmlWithAttachment(
            String to,
            String subject,
            String html,
            String attachmentName,
            byte[] attachment
    ) {
        if (to == null || to.isBlank()) {
            return;
        }
        DeliveryResult result = deliverHtmlWithAttachment(to, subject, html, attachmentName, attachment);
        if (!result.success()) {
            log.info("Email attachment not sent to {}: {}", to, result.message());
        }
    }

    public DeliveryResult deliverHtml(String to, String subject, String html) {
        try {
            sendNow(to, subject, plainText(html), html, null, null);
            return new DeliveryResult(true, "E-mail envoye a " + to);
        } catch (Exception exception) {
            return new DeliveryResult(false, rootMessage(exception));
        }
    }

    public DeliveryResult deliverHtmlWithAttachment(
            String to,
            String subject,
            String html,
            String attachmentName,
            byte[] attachment
    ) {
        try {
            sendNow(to, subject, plainText(html), html, attachmentName, attachment);
            return new DeliveryResult(true, "E-mail avec pièce jointe envoyé à " + to);
        } catch (Exception exception) {
            return new DeliveryResult(false, rootMessage(exception));
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", host != null && !host.isBlank() && port > 0);
        status.put("host", host);
        status.put("port", port);
        status.put("username", maskEmail(username));
        status.put("fromAddress", maskEmail(fromAddress));
        status.put("fromName", fromName);
        status.put("authentication", authentication);
        status.put("startTls", startTls);
        status.put("ssl", ssl);
        return status;
    }

    private void sendNow(
            String to,
            String subject,
            String text,
            String html,
            String attachmentName,
            byte[] attachment
    ) throws Exception {
        var message = mailSender.createMimeMessage();
        boolean hasAttachment = attachment != null && attachment.length > 0;
        boolean multipart = html != null || hasAttachment;
        MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress, fromName);
        helper.setTo(to);
        helper.setSubject(subject);
        if (html == null) {
            helper.setText(text, false);
        } else {
            helper.setText(text, html);
        }
        if (hasAttachment) {
            helper.addAttachment(attachmentName, new ByteArrayResource(attachment), "application/pdf");
        }
        mailSender.send(message);
    }

    private String plainText(String html) {
        return html == null ? "" : html
                .replaceAll("(?is)<style.*?>.*?</style>", "")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String maskEmail(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int separator = value.indexOf('@');
        if (separator <= 1) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(separator);
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record DeliveryResult(boolean success, String message) {
    }
}
