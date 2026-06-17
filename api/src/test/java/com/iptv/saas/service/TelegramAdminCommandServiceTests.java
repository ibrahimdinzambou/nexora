package com.iptv.saas.service;

import com.iptv.saas.domain.CommunityAddon;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.CommunityAddonRepository;
import com.iptv.saas.repository.InvoiceRepository;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.PaymentTransactionRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.repository.SupportTicketRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAdminCommandServiceTests {
    @Test
    void addsM3uAccountWithoutAskingForMaxStreams() {
        Fixture fixture = fixture();
        UserEntity admin = admin();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin));
        IptvAccount saved = new IptvAccount();
        saved.id = 42L;
        saved.name = "Fournisseur";
        saved.accountType = Enums.IptvAccountType.M3U;
        saved.lastHealthStatus = "ok";
        saved.maxStreams = 1;
        when(fixture.catalog.saveAccount(
                eq(null),
                eq("Fournisseur"),
                eq(Enums.IptvAccountType.M3U),
                eq(null),
                eq(null),
                eq(null),
                eq("https://example.test/list.m3u"),
                eq(null),
                eq(true),
                eq(null)
        )).thenReturn(saved);
        when(fixture.catalog.syncLimits(42L)).thenReturn(Map.of("maxStreams", 1, "activeStreams", 0, "health", "ok", "detected", false));

        TelegramAdminCommandService.Response response = fixture.service.handle(
                "/add_m3u Fournisseur | https://example.test/list.m3u",
                "chat-1",
                false
        );

        assertTrue(response.text().contains("Compte M3U ajoute"));
        assertTrue(response.text().contains("#42"));
        verify(fixture.audit).log(admin, "telegram.iptv.account.created", "IptvAccount", 42L, "M3U Fournisseur");
    }

    @Test
    void assignsPrivateAddonToSelectedUsers() {
        Fixture fixture = fixture();
        UserEntity admin = admin();
        UserEntity user = user(5L, "client@example.test");
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin));
        CommunityAddon addon = new CommunityAddon();
        addon.id = 22L;
        addon.name = "Private";
        addon.privateUse = true;
        addon.allowedUsers.add(user);
        when(fixture.addons.updateAccess(eq(22L), eq(List.of(5L)), eq(admin))).thenReturn(addon);

        TelegramAdminCommandService.Response response = fixture.service.handle("/assign_addon 22 5", "chat-1", false);

        assertTrue(response.text().contains("Acces add-on mis a jour"));
        assertTrue(response.text().contains("client@example.test"));
        verify(fixture.audit).log(admin, "telegram.addon.access.updated", "CommunityAddon", 22L, String.valueOf(List.of(5L)));
    }

    @Test
    void rejectsWriteCommandsForReadOnlyChats() {
        Fixture fixture = fixture();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin()));

        TelegramAdminCommandService.Response response = fixture.service.handle("/disable_account 33", "readonly", true);

        assertTrue(response.text().contains("Mode lecture seule"));
    }

    @Test
    void verifiesPaymentFromInlineCallback() {
        Fixture fixture = fixture();
        UserEntity admin = admin();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin));
        PaymentTransaction payment = new PaymentTransaction();
        payment.id = 9L;
        payment.paymentReference = "PAY-9";
        payment.amount = BigDecimal.TEN;
        payment.currency = "XOF";
        payment.status = Enums.PaymentStatus.VERIFIED;
        when(fixture.billing.verifyPayment(admin, 9L)).thenReturn(payment);

        TelegramAdminCommandService.Response response = fixture.service.handleCallback(
                "do:" + Instant.now().plusSeconds(60).getEpochSecond() + ":verify_payment:9",
                "chat-1",
                false
        );

        assertTrue(response.text().contains("Paiement valide"));
    }

    @Test
    void reportsWhoamiForChatContext() {
        Fixture fixture = fixture();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin()));

        TelegramAdminCommandService.Response response = fixture.service.handle(
                "/whoami",
                new TelegramAdminCommandService.ChatContext("12345", Enums.UserRole.OPS, true, Map.of())
        );

        assertTrue(response.text().contains("12345"));
        assertTrue(response.text().contains("OPS"));
        assertTrue(response.text().contains("oui"));
    }

    @Test
    void rejectsCommandsOutsideChatRole() {
        Fixture fixture = fixture();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin()));

        TelegramAdminCommandService.Response response = fixture.service.handle(
                "/pending_payments",
                new TelegramAdminCommandService.ChatContext("ops", Enums.UserRole.OPS, false, Map.of())
        );

        assertTrue(response.text().contains("Acces refuse"));
    }

    @Test
    void rejectsExpiredConfirmationCallbacks() {
        Fixture fixture = fixture();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin()));

        TelegramAdminCommandService.Response response = fixture.service.handleCallback(
                "do:" + Instant.now().minusSeconds(60).getEpochSecond() + ":verify_payment:9",
                "chat-1",
                false
        );

        assertTrue(response.text().contains("expiree"));
    }

    @Test
    void mainMenuShowsIptvAddEntry() {
        Fixture fixture = fixture();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin()));

        TelegramAdminCommandService.Response response = fixture.service.handle("/admin", "chat-1", false);

        assertTrue(response.text().contains("/add_m3u"));
        assertTrue(response.keyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.text().contains("Ajouter M3U")));
    }

    @Test
    void addIptvMenuExplainsM3uAndXtreamFormats() {
        Fixture fixture = fixture();
        when(fixture.users.findByActive(true)).thenReturn(List.of(admin()));

        TelegramAdminCommandService.Response response = fixture.service.handleCallback("menu:iptv_add", "chat-1", false);

        assertTrue(response.text().contains("Ajouter une source IPTV"));
        assertTrue(response.text().contains("M3U"));
        assertTrue(response.text().contains("Xtream Codes"));
        assertTrue(response.keyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.callbackData().equals("menu:add_m3u_help")));
    }

    private Fixture fixture() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        StreamingService streaming = mock(StreamingService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        CommunityAddonRepository addonRepository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        BillingService billing = mock(BillingService.class);
        InvoiceService invoiceService = mock(InvoiceService.class);
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        SupportService support = mock(SupportService.class);
        TransactionalMailService mail = mock(TransactionalMailService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        TorBoxTorrentResolver torBox = mock(TorBoxTorrentResolver.class);
        OpsService ops = mock(OpsService.class);
        AuditService audit = mock(AuditService.class);
        TelegramAdminCommandService service = new TelegramAdminCommandService(
                catalog,
                accounts,
                sessions,
                streaming,
                addons,
                addonRepository,
                users,
                subscriptions,
                payments,
                billing,
                invoiceService,
                invoices,
                tickets,
                support,
                mail,
                telegram,
                torBox,
                ops,
                audit,
                Optional.empty()
        );
        return new Fixture(catalog, accounts, sessions, streaming, addons, addonRepository, users, subscriptions,
                payments, billing, invoiceService, invoices, tickets, support, mail, telegram, torBox, ops, audit, service);
    }

    private UserEntity admin() {
        UserEntity user = user(1L, "admin@example.test");
        user.role = Enums.UserRole.SUPER_ADMIN;
        return user;
    }

    private UserEntity user(Long id, String email) {
        UserEntity user = new UserEntity();
        user.id = id;
        user.name = email.substring(0, email.indexOf('@'));
        user.email = email;
        user.role = Enums.UserRole.USER;
        user.active = true;
        return user;
    }

    private record Fixture(
            IptvCatalogService catalog,
            IptvAccountRepository accounts,
            UserSessionRepository sessions,
            StreamingService streaming,
            CommunityAddonService addons,
            CommunityAddonRepository addonRepository,
            UserRepository users,
            SubscriptionRepository subscriptions,
            PaymentTransactionRepository payments,
            BillingService billing,
            InvoiceService invoiceService,
            InvoiceRepository invoices,
            SupportTicketRepository tickets,
            SupportService support,
            TransactionalMailService mail,
            TelegramAlertService telegram,
            TorBoxTorrentResolver torBox,
            OpsService ops,
            AuditService audit,
            TelegramAdminCommandService service
    ) {
    }
}
