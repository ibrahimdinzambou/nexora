package com.iptv.saas.web;

import com.iptv.saas.service.AuditService;
import com.iptv.saas.service.EmailTemplateService;
import com.iptv.saas.service.InvoicePdfService;
import com.iptv.saas.service.TelegramAdminBotService;
import com.iptv.saas.service.TelegramAlertService;
import com.iptv.saas.service.TransactionalMailService;
import com.iptv.saas.service.TorBoxTorrentResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminIntegrationControllerTests {
    @Test
    void exposesMaskedIntegrationStatus() {
        TransactionalMailService mail = mock(TransactionalMailService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        TelegramAdminBotService telegramAdmin = mock(TelegramAdminBotService.class);
        EmailTemplateService templates = mock(EmailTemplateService.class);
        InvoicePdfService invoicePdf = mock(InvoicePdfService.class);
        AuditService audit = mock(AuditService.class);
        TorBoxTorrentResolver torBox = mock(TorBoxTorrentResolver.class);
        when(mail.status()).thenReturn(Map.of("configured", true, "username", "a***@example.test"));
        when(telegram.status()).thenReturn(Map.of("configured", false, "botToken", ""));
        when(telegramAdmin.status()).thenReturn(Map.of("configured", true, "polling", true));
        when(torBox.status()).thenReturn(Map.of("configured", true, "provider", "TorBox"));
        AdminIntegrationController controller = new AdminIntegrationController(
                mail,
                telegram,
                telegramAdmin,
                templates,
                invoicePdf,
                audit,
                torBox
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.status();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(true, ((Map<?, ?>) data.get("smtp")).get("configured"));
        assertEquals(false, ((Map<?, ?>) data.get("telegram")).get("configured"));
        assertEquals(true, ((Map<?, ?>) data.get("telegramAdmin")).get("configured"));
        assertEquals(true, ((Map<?, ?>) data.get("torbox")).get("configured"));
    }
}
