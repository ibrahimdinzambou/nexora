package com.iptv.saas.service;

import com.iptv.saas.domain.CommunityAddon;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Invoice;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.SupportTicket;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.repository.CommunityAddonRepository;
import com.iptv.saas.repository.InvoiceRepository;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.PaymentTransactionRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.repository.SupportTicketRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.web.ApiException;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TelegramAdminCommandService {
    private static final int LIST_LIMIT = 15;
    private static final Duration STALE_SESSION_AGE = Duration.ofSeconds(90);
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "/add_m3u",
            "/add_xtream",
            "/clear_cache",
            "/sync_limits",
            "/enable_account",
            "/disable_account",
            "/close_session",
            "/cleanup_sessions",
            "/suspend_user",
            "/reactivate_user",
            "/message_user",
            "/verify_payment",
            "/reject_payment",
            "/invoice_for_payment",
            "/resend_invoice",
            "/reply_ticket",
            "/close_ticket",
            "/answer_ticket",
            "/assign_ticket",
            "/approve_addon",
            "/disable_addon",
            "/assign_addon",
            "/smtp_test",
            "/telegram_test"
    );

    private final IptvCatalogService catalog;
    private final IptvAccountRepository accounts;
    private final UserSessionRepository sessions;
    private final StreamingService streaming;
    private final CommunityAddonService addons;
    private final CommunityAddonRepository addonRepository;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final PaymentTransactionRepository payments;
    private final BillingService billing;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final SupportTicketRepository tickets;
    private final SupportService support;
    private final TransactionalMailService mail;
    private final TelegramAlertService telegram;
    private final TorBoxTorrentResolver torBox;
    private final OpsService ops;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Optional<BuildProperties> buildProperties;

    public TelegramAdminCommandService(
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
            NotificationService notifications,
            Optional<BuildProperties> buildProperties
    ) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.sessions = sessions;
        this.streaming = streaming;
        this.addons = addons;
        this.addonRepository = addonRepository;
        this.users = users;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.billing = billing;
        this.invoiceService = invoiceService;
        this.invoices = invoices;
        this.tickets = tickets;
        this.support = support;
        this.mail = mail;
        this.telegram = telegram;
        this.torBox = torBox;
        this.ops = ops;
        this.audit = audit;
        this.notifications = notifications;
        this.buildProperties = buildProperties;
    }

    @Transactional
    public Response handle(String rawText, String chatId, boolean readOnly) {
        return handle(rawText, new ChatContext(chatId, Enums.UserRole.SUPER_ADMIN, readOnly, Map.of()));
    }

    @Transactional
    public Response handle(String rawText, ChatContext context) {
        String text = rawText == null ? "" : rawText.strip();
        String command = text.isBlank() ? "/admin" : command(text);
        UserEntity actor = adminActor();
        audit.log(actor, "telegram.command", "Telegram", null, command + " chat=" + context.chatId());
        if (context.readOnly() && WRITE_COMMANDS.contains(command)) {
            return text("Mode lecture seule: action refusee pour ce chat.");
        }
        if (!allowedForRole(context.role(), command)) {
            return text("Acces refuse: role " + context.role() + " insuffisant pour " + command + ".");
        }
        if (text.isBlank() || "/start".equals(command) || "/admin".equals(command) || "/help".equals(command)) {
            return menu();
        }
        if (command.startsWith("/account_")) {
            return safe(() -> account(command.substring("/account_".length())), actor, command);
        }
        return safe(() -> switch (command) {
            case "/whoami" -> whoami(context);
            case "/admin_status" -> adminStatus(context);
            case "/status" -> status();
            case "/health" -> health();
            case "/sessions" -> sessionsSummary();
            case "/capacity" -> capacity();
            case "/accounts", "/iptv" -> accounts();
            case "/add_m3u" -> addM3u(arguments(text), actor);
            case "/add_xtream" -> addXtream(arguments(text), actor);
            case "/account" -> account(arguments(text));
            case "/test_account" -> testAccount(arguments(text));
            case "/clear_cache" -> clearCache(arguments(text), actor);
            case "/sync_limits" -> syncLimits(arguments(text), actor);
            case "/enable_account" -> enableAccount(arguments(text), actor);
            case "/disable_account" -> confirm("Desactiver ce fournisseur IPTV ?", "disable_account:" + requireId(arguments(text), "accountId"));
            case "/accounts_warning" -> warningAccounts();
            case "/active_sessions" -> activeSessions();
            case "/close_session" -> confirm("Fermer cette session de lecture ?", "close_session:" + requireId(arguments(text), "sessionId"));
            case "/stale_sessions" -> staleSessions();
            case "/cleanup_sessions" -> cleanupSessions(actor);
            case "/client" -> client(arguments(text));
            case "/categories" -> categories(arguments(text));
            case "/suspend_user" -> confirm("Suspendre ce client ?", "suspend_user:" + requireId(arguments(text), "userId"));
            case "/reactivate_user" -> reactivateUser(arguments(text), actor);
            case "/message_user" -> messageUser(arguments(text), actor);
            case "/pending_payments" -> pendingPayments();
            case "/verify_payment" -> confirm("Valider ce paiement manuel ?", "verify_payment:" + requireId(arguments(text), "paymentId"));
            case "/reject_payment" -> confirm("Refuser ce paiement manuel ?", "reject_payment:" + rejectPayload(arguments(text)));
            case "/expiring_subscriptions" -> expiringSubscriptions(arguments(text));
            case "/invoice_for_payment" -> invoiceForPayment(arguments(text), actor);
            case "/resend_invoice" -> resendInvoice(arguments(text), actor);
            case "/tickets" -> openTickets();
            case "/urgent_tickets" -> urgentTickets();
            case "/reply_ticket" -> replyTicket(arguments(text), actor);
            case "/close_ticket" -> closeTicket(arguments(text), actor);
            case "/answer_ticket" -> answerTicket(arguments(text), actor);
            case "/assign_ticket" -> assignTicket(arguments(text), actor);
            case "/smtp_status" -> mapStatus("SMTP", mail.status());
            case "/smtp_test" -> smtpTest(arguments(text), actor);
            case "/telegram_status" -> mapStatus("Telegram", telegram.status());
            case "/telegram_test" -> telegramTest(actor);
            case "/torbox_status" -> mapStatus("TorBox", torBox.status());
            case "/pending_addons" -> pendingAddons();
            case "/approve_addon" -> confirm("Approuver cet add-on ?", "approve_addon:" + requireId(arguments(text), "addonId"));
            case "/disable_addon" -> confirm("Desactiver cet add-on ?", "disable_addon:" + requireId(arguments(text), "addonId"));
            case "/addons" -> addons();
            case "/users" -> users(arguments(text));
            case "/assign_addon" -> assignAddon(arguments(text), actor);
            default -> text("Commande inconnue.\n\n" + helpText(), menuKeyboard());
        }, actor, command);
    }

    @Transactional
    public Response handleCallback(String data, String chatId, boolean readOnly) {
        return handleCallback(data, new ChatContext(chatId, Enums.UserRole.SUPER_ADMIN, readOnly, Map.of()));
    }

    @Transactional
    public Response handleCallback(String data, ChatContext context) {
        UserEntity actor = adminActor();
        String value = data == null ? "" : data.strip();
        audit.log(actor, "telegram.callback", "Telegram", null, value + " chat=" + context.chatId());
        if (value.startsWith("menu:")) {
            String menu = value.substring("menu:".length());
            String menuCommand = switch (menu) {
                case "iptv" -> "/accounts";
                case "iptv_add", "add_m3u_help", "add_xtream_help" -> "/add_m3u";
                case "clients" -> "/users";
                case "billing" -> "/pending_payments";
                case "support" -> "/tickets";
                case "system" -> "/status";
                default -> "/admin";
            };
            if (!allowedForRole(context.role(), menuCommand)) {
                return text("Acces refuse: role " + context.role() + " insuffisant pour cette rubrique.");
            }
            return switch (menu) {
                case "iptv" -> accounts();
                case "iptv_add" -> iptvAddMenu();
                case "add_m3u_help" -> addM3uHelp();
                case "add_xtream_help" -> addXtreamHelp();
                case "clients" -> users("");
                case "billing" -> pendingPayments();
                case "support" -> openTickets();
                case "system" -> status();
                default -> menu();
            };
        }
        if (value.startsWith("confirm:")) {
            if (context.readOnly()) {
                return text("Mode lecture seule: action refusee pour ce chat.");
            }
            ConfirmedAction confirmed = confirmedAction(value.substring("confirm:".length()));
            if (confirmed.expired()) {
                return text("Confirmation expiree. Relancez la commande pour generer un nouveau bouton.");
            }
            if (!allowedForRole(context.role(), commandForCallback(confirmed.action()))) {
                return text("Acces refuse: role " + context.role() + " insuffisant pour cette action.");
            }
            return text("Confirmation requise", List.of(
                    List.of(button("Executer", "do:" + confirmed.expiresAtEpochSecond() + ":" + confirmed.action()), button("Annuler", "cancel"))
            ));
        }
        if ("cancel".equals(value)) {
            return text("Action annulee.", menuKeyboard());
        }
        if (context.readOnly() && isWriteCallback(value)) {
            return text("Mode lecture seule: action refusee pour ce chat.");
        }
        if (!allowedForRole(context.role(), commandForCallback(value))) {
            return text("Acces refuse: role " + context.role() + " insuffisant pour cette action.");
        }
        if (value.startsWith("do:")) {
            ConfirmedAction confirmed = confirmedAction(value.substring("do:".length()));
            if (confirmed.expired()) {
                return text("Confirmation expiree. Relancez la commande pour generer un nouveau bouton.");
            }
            return safe(() -> executeCallback(confirmed.action(), actor), actor, "do");
        }
        return safe(() -> executeCallback(value, actor), actor, "callback");
    }

    private Response executeCallback(String action, UserEntity actor) {
        String[] parts = action.split(":", 3);
        String name = parts[0];
        String first = parts.length > 1 ? parts[1] : "";
        String second = parts.length > 2 ? parts[2] : "";
        return switch (name) {
            case "test_account" -> testAccount(first);
            case "account" -> account(first);
            case "client" -> client(first);
            case "payment" -> payment(first);
            case "clear_cache" -> clearCache(first, actor);
            case "sync_limits" -> syncLimits(first, actor);
            case "disable_account" -> disableAccount(first, actor);
            case "enable_account" -> enableAccount(first, actor);
            case "close_session" -> closeSession(first, actor);
            case "suspend_user" -> suspendUser(first, actor);
            case "reactivate_user" -> reactivateUser(first, actor);
            case "verify_payment" -> verifyPayment(first, actor);
            case "reject_payment" -> rejectPayment(first + (second.isBlank() ? "" : " | " + second), actor);
            case "invoice_for_payment" -> invoiceForPayment(first, actor);
            case "close_ticket" -> closeTicket(first, actor);
            case "answer_ticket" -> answerTicket(first, actor);
            case "approve_addon" -> approveAddon(first, actor);
            case "disable_addon" -> disableAddon(first, actor);
            default -> text("Action inconnue.");
        };
    }

    private Response menu() {
        return text(helpText(), menuKeyboard());
    }

    private String helpText() {
        return """
                Nexora Admin Panel

                Choisissez une action avec les boutons ci-dessous.

                Demarrage rapide:
                /whoami pour verifier votre chat_id
                /admin_status pour verifier la config du bot
                /accounts pour gerer les sources IPTV
                /add_m3u Nom | URL playlist
                /add_xtream Nom | URL base | username | password

                Sections:
                Supervision: /status /health /sessions /capacity
                IPTV: /accounts /account_33 /test_account 33
                Sessions: /active_sessions /stale_sessions /cleanup_sessions
                Clients: /users /client email
                Paiements: /pending_payments
                Support: /tickets /urgent_tickets
                """.strip();
    }

    private List<List<Button>> menuKeyboard() {
        return List.of(
                List.of(button("TV / Sources IPTV", "menu:iptv")),
                List.of(button("Ajouter M3U / Xtream", "menu:iptv_add")),
                List.of(button("Clients", "menu:clients"), button("Paiements", "menu:billing")),
                List.of(button("Support", "menu:support"), button("Systeme", "menu:system"))
        );
    }

    private Response iptvAddMenu() {
        return text("""
                Ajouter une source IPTV

                Choisissez le type de compte a ajouter.

                M3U: une URL de playlist .m3u ou .m3u8.
                Xtream Codes: URL serveur + identifiant + mot de passe.

                Le bot cree le compte, tente une synchro, puis l'ajoute a la rotation IPTV.
                """, List.of(
                List.of(button("Modele M3U", "menu:add_m3u_help"), button("Modele Xtream", "menu:add_xtream_help")),
                List.of(button("Voir les comptes", "menu:iptv"), button("Retour menu", "menu:home"))
        ));
    }

    private Response addM3uHelp() {
        return text("""
                Ajouter une playlist M3U

                Copiez ce modele, remplacez les valeurs, puis envoyez-le au bot:

                /add_m3u Nom fournisseur | https://exemple.com/playlist.m3u

                Exemple:
                /add_m3u Premium FR | https://provider.example/live/list.m3u
                """, List.of(
                List.of(button("Modele Xtream", "menu:add_xtream_help")),
                List.of(button("Retour ajout", "menu:iptv_add"), button("Voir comptes", "menu:iptv"))
        ));
    }

    private Response addXtreamHelp() {
        return text("""
                Ajouter un compte Xtream Codes

                Copiez ce modele, remplacez les valeurs, puis envoyez-le au bot:

                /add_xtream Nom fournisseur | https://serveur.example:8080 | username | password

                Exemple:
                /add_xtream Premium XC | http://panel.example.com:8080 | client01 | secret
                """, List.of(
                List.of(button("Modele M3U", "menu:add_m3u_help")),
                List.of(button("Retour ajout", "menu:iptv_add"), button("Voir comptes", "menu:iptv"))
        ));
    }

    private Response whoami(ChatContext context) {
        return text("""
                Identite Telegram

                Chat ID: `%s`
                Role: `%s`
                Lecture seule: `%s`
                """.formatted(context.chatId(), context.role(), context.readOnly() ? "oui" : "non"));
    }

    private Response adminStatus(ChatContext context) {
        Map<String, Object> status = context.adminStatus() == null ? Map.of() : context.adminStatus();
        return text("""
                Bot admin Telegram

                Active: %s
                Configure: %s
                Token: %s
                Chat principal: %s
                Chats autorises: %s
                Chats lecture seule: %s
                Roles: %s
                Polling: %s
                Intervalle polling: %s ms
                Dernier polling: %s
                Endpoint: %s
                """.formatted(
                status.getOrDefault("enabled", "-"),
                status.getOrDefault("configured", "-"),
                status.getOrDefault("botToken", "-"),
                status.getOrDefault("primaryChatId", "-"),
                status.getOrDefault("allowedChatIds", List.of()),
                status.getOrDefault("readOnlyChatIds", List.of()),
                status.getOrDefault("chatRoles", Map.of()),
                status.getOrDefault("polling", "-"),
                status.getOrDefault("pollIntervalMs", "-"),
                status.getOrDefault("lastPollAt", "-"),
                status.getOrDefault("endpoint", "api.telegram.org")
        ), menuKeyboard());
    }

    private Response status() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        Map<String, Object> health = ops.health();
        String version = buildProperties.map(BuildProperties::getVersion).orElse("dev");
        return text("""
                Status global

                API: UP
                Version: %s
                Uptime: %s
                Base H2: %s
                Memoire: %d/%d MB
                IPTV: %s
                """.formatted(
                version,
                duration(uptimeSeconds),
                health.get("database"),
                usedMb,
                maxMb,
                health.get("status")
        ), menuKeyboard());
    }

    private Response health() {
        List<IptvAccount> values = visibleAccounts();
        if (values.isEmpty()) {
            return text("Aucun compte IPTV configure.");
        }
        return text("Sante IPTV\n\n" + values.stream()
                .map(account -> {
                    String fresh = catalog.health(account);
                    if (!fresh.equals(account.lastHealthStatus)) {
                        account.lastHealthStatus = fresh;
                        accounts.save(account);
                    }
                    return "#%d %s [%s]\n%s"
                            .formatted(account.id, account.name, account.accountType, fresh);
                })
                .collect(Collectors.joining("\n\n")));
    }

    private Response sessionsSummary() {
        long active = sessions.countByStatus(Enums.SessionStatus.ACTIVE);
        long stale = staleSessionList().size();
        return text("""
                Sessions

                Actives: %d
                Sans heartbeat recent: %d
                Total: %d

                Utilisez /active_sessions pour les details.
                """.formatted(active, stale, sessions.count()));
    }

    private Response capacity() {
        List<IptvAccount> values = visibleAccounts();
        int active = values.stream().mapToInt(account -> account.activeStreams).sum();
        int capacity = values.stream().mapToInt(account -> Math.max(account.maxStreams, 0)).sum();
        int percent = capacity <= 0 ? 0 : Math.round(active * 100f / capacity);
        return text("""
                Capacite IPTV

                Charge actuelle: %d
                Capacite max: %s
                Utilisation: %d%%
                Fournisseurs actifs: %d
                """.formatted(active, capacity <= 0 ? "illimitee/inconnue" : String.valueOf(capacity), percent, accounts.countByActiveTrue()));
    }

    private Response accounts() {
        List<IptvAccount> values = visibleAccounts();
        if (values.isEmpty()) {
            return text("""
                    Aucune source IPTV configuree

                    Ajoutez une playlist M3U ou un compte Xtream Codes pour commencer.
                    """, List.of(
                    List.of(button("Ajouter M3U / Xtream", "menu:iptv_add")),
                    List.of(button("Modele M3U", "menu:add_m3u_help"), button("Modele Xtream", "menu:add_xtream_help"))
            ));
        }
        List<List<Button>> keyboard = new ArrayList<>();
        keyboard.add(List.of(button("Ajouter M3U / Xtream", "menu:iptv_add")));
        values.stream().limit(5).forEach(account -> keyboard.add(List.of(
                button("Fiche #" + account.id, "account:" + account.id),
                button("Tester #" + account.id, "test_account:" + account.id),
                button("Synch #" + account.id, "sync_limits:" + account.id)
        )));
        values.stream().limit(5).forEach(account -> keyboard.add(List.of(
                button("Cache #" + account.id, "clear_cache:" + account.id)
        )));
        values.stream().limit(5).forEach(account -> keyboard.add(List.of(
                button(account.disabled || !account.active ? "Activer #" + account.id : "Desactiver #" + account.id,
                        account.disabled || !account.active
                                ? "enable_account:" + account.id
                                : confirmCallback("disable_account:" + account.id))
        )));
        return text("Comptes IPTV\n\n" + values.stream()
                .limit(LIST_LIMIT)
                .map(this::accountLine)
                .collect(Collectors.joining("\n\n")), keyboard);
    }

    private Response account(String value) {
        IptvAccount account = accountById(requireId(value, "accountId"));
        return text("""
                Compte IPTV #%d

                Nom: %s
                Type: %s
                Statut: %s
                Actif: %s
                Desactive: %s
                Charge: %d/%s
                Expire: %s
                URL: %s
                Echecs: %d
                """.formatted(
                account.id,
                account.name,
                account.accountType,
                catalog.health(account),
                account.active ? "oui" : "non",
                account.disabled ? "oui" : "non",
                account.activeStreams,
                account.maxStreams <= 0 ? "infini" : String.valueOf(account.maxStreams),
                date(account.expiresAt),
                sourceUrl(account),
                account.failureCount
        ), List.of(
                List.of(button("Tester", "test_account:" + account.id), button("Synchroniser", "sync_limits:" + account.id)),
                List.of(button("Vider cache", "clear_cache:" + account.id),
                        button(account.disabled || !account.active ? "Activer" : "Desactiver",
                                account.disabled || !account.active
                                        ? "enable_account:" + account.id
                                        : confirmCallback("disable_account:" + account.id)))
        ));
    }

    private Response testAccount(String value) {
        IptvAccount account = accountById(requireId(value, "accountId"));
        String status = catalog.health(account);
        account.lastHealthStatus = status;
        accounts.save(account);
        return text("Test IPTV #%d %s\nSante: %s".formatted(account.id, account.name, status));
    }

    private Response clearCache(String value, UserEntity actor) {
        Long id = requireId(value, "accountId");
        Map<String, Object> result = catalog.refreshCache(id);
        audit.log(actor, "telegram.iptv.cache.refresh", "IptvAccount", id, String.valueOf(result));
        return text("Cache vide pour le compte #" + id);
    }

    private Response syncLimits(String value, UserEntity actor) {
        Long id = requireId(value, "accountId");
        Map<String, Object> result = catalog.syncLimits(id);
        audit.log(actor, "telegram.iptv.limits.sync", "IptvAccount", id, String.valueOf(result));
        return text("Limites synchronisees pour #" + id + "\n" + mapLines(result));
    }

    private Response enableAccount(String value, UserEntity actor) {
        IptvAccount account = accountById(requireId(value, "accountId"));
        account.active = true;
        account.disabled = false;
        account.disabledReason = null;
        account.lastHealthStatus = catalog.health(account);
        accounts.save(account);
        audit.log(actor, "telegram.iptv.account.enabled", "IptvAccount", account.id, account.name);
        return text("Compte active: #" + account.id + " " + account.name);
    }

    private Response disableAccount(String value, UserEntity actor) {
        IptvAccount account = accountById(requireId(value, "accountId"));
        sessions.findByIptvAccountAndStatus(account, Enums.SessionStatus.ACTIVE)
                .forEach(session -> streaming.closeByAdmin(actor, session.id));
        account.active = false;
        account.disabled = true;
        account.activeStreams = 0;
        account.lastHealthStatus = "disabled";
        account.disabledReason = "telegram";
        accounts.save(account);
        audit.log(actor, "telegram.iptv.account.disabled", "IptvAccount", account.id, account.name);
        return text("Compte desactive: #" + account.id + " " + account.name);
    }

    private Response warningAccounts() {
        List<IptvAccount> values = visibleAccounts().stream()
                .filter(account -> !"ok".equalsIgnoreCase(catalog.health(account)) || expiresSoon(account))
                .toList();
        if (values.isEmpty()) {
            return text("Aucun compte a surveiller.");
        }
        return text("Comptes a surveiller\n\n" + values.stream()
                .map(this::accountLine)
                .collect(Collectors.joining("\n\n")));
    }

    private Response activeSessions() {
        List<UserSession> values = sessions.findByStatus(Enums.SessionStatus.ACTIVE);
        if (values.isEmpty()) {
            return text("Aucune session active.");
        }
        List<List<Button>> keyboard = values.stream()
                .limit(5)
                .map(session -> List.of(button("Fermer #" + session.id, confirmCallback("close_session:" + session.id))))
                .toList();
        return text("Sessions actives\n\n" + values.stream()
                .limit(LIST_LIMIT)
                .map(this::sessionLine)
                .collect(Collectors.joining("\n\n")), keyboard);
    }

    private Response closeSession(String value, UserEntity actor) {
        Long id = requireId(value, "sessionId");
        streaming.closeByAdmin(actor, id);
        audit.log(actor, "telegram.stream.closed", "UserSession", id, null);
        return text("Session fermee: #" + id);
    }

    private Response staleSessions() {
        List<UserSession> values = staleSessionList();
        if (values.isEmpty()) {
            return text("Aucune session sans heartbeat recent.");
        }
        return text("Sessions bloquees ou sans heartbeat\n\n" + values.stream()
                .limit(LIST_LIMIT)
                .map(this::sessionLine)
                .collect(Collectors.joining("\n\n")));
    }

    private Response cleanupSessions(UserEntity actor) {
        int cleaned = streaming.cleanupInactive();
        audit.log(actor, "telegram.stream.cleanup", "UserSession", null, String.valueOf(cleaned));
        return text(cleaned + " session(s) fantome(s) nettoyee(s).");
    }

    private Response client(String value) {
        UserEntity user = findUser(value);
        Subscription subscription = user.currentOrganization == null
                ? null
                : subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(user.currentOrganization).orElse(null);
        return text("""
                Client #%d

                Nom: %s
                Email: %s
                Role: %s
                Actif: %s
                Organisation: %s
                Abonnement: %s
                Plan: %s
                Fin periode: %s
                Categories: %s
                """.formatted(
                user.id,
                user.name,
                user.email,
                user.role,
                user.active ? "oui" : "non",
                user.currentOrganization == null ? "-" : user.currentOrganization.name,
                subscription == null ? "-" : subscription.status,
                subscription == null || subscription.plan == null ? "-" : subscription.plan.name,
                subscription == null ? "-" : date(subscription.currentPeriodEnd),
                user.allowedCategories
        ), List.of(
                List.of(button("Suspendre", confirmCallback("suspend_user:" + user.id)), button("Reactiver", "reactivate_user:" + user.id))
        ));
    }

    private Response categories(String value) {
        UserEntity user = findUser(value);
        return text("Categories autorisees pour #%d %s\n%s".formatted(user.id, user.email, user.allowedCategories));
    }

    private Response suspendUser(String value, UserEntity actor) {
        UserEntity user = userById(requireId(value, "userId"));
        user.active = false;
        if (user.currentOrganization != null) {
            user.currentOrganization.status = Enums.OrganizationStatus.SUSPENDED;
        }
        users.save(user);
        audit.log(actor, "telegram.user.suspended", "User", user.id, user.email);
        return text("Client suspendu: #" + user.id + " " + user.email);
    }

    private Response reactivateUser(String value, UserEntity actor) {
        UserEntity user = userById(requireId(value, "userId"));
        user.active = true;
        if (user.currentOrganization != null) {
            user.currentOrganization.status = Enums.OrganizationStatus.ACTIVE;
        }
        users.save(user);
        audit.log(actor, "telegram.user.reactivated", "User", user.id, user.email);
        return text("Client reactive: #" + user.id + " " + user.email);
    }

    private Response messageUser(String value, UserEntity actor) {
        List<String> parts = splitPipe(value);
        if (parts.size() < 2) {
            throw new IllegalArgumentException("/message_user userId | message");
        }
        UserEntity user = userById(parseLong(parts.get(0), "userId"));
        String body = require(parts.get(1), "message");
        mail.send(user.email, "Message Nexora", body);
        notifications.notifyUser(user, "Message Nexora", body, "message", "/watch.html");
        audit.log(actor, "telegram.user.message", "User", user.id, body);
        return text("Message envoye a " + user.email);
    }

    private Response pendingPayments() {
        List<PaymentTransaction> values = payments.findByStatusOrderByCreatedAtDesc(Enums.PaymentStatus.PENDING);
        if (values.isEmpty()) {
            return text("Aucun paiement en attente.");
        }
        List<List<Button>> keyboard = values.stream().limit(5)
                .map(payment -> List.of(
                        button("Fiche #" + payment.id, "payment:" + payment.id),
                        button("Valider #" + payment.id, confirmCallback("verify_payment:" + payment.id)),
                        button("Refuser #" + payment.id, confirmCallback("reject_payment:" + payment.id))
                ))
                .toList();
        return text("Paiements en attente\n\n" + values.stream()
                .limit(LIST_LIMIT)
                .map(this::paymentLine)
                .collect(Collectors.joining("\n\n")), keyboard);
    }

    private Response payment(String value) {
        PaymentTransaction payment = payments.findById(requireId(value, "paymentId"))
                .orElseThrow(() -> ApiException.notFound("Paiement introuvable"));
        return text("""
                Paiement #%d

                Reference: %s
                Statut: %s
                Montant: %s %s
                Client: #%s %s
                Methode: %s
                Plan: %s
                Cree le: %s
                Verifie le: %s
                """.formatted(
                payment.id,
                payment.paymentReference,
                payment.status,
                payment.amount,
                payment.currency,
                payment.user == null ? "-" : payment.user.id,
                payment.user == null ? "-" : payment.user.email,
                payment.paymentMethod == null ? "-" : payment.paymentMethod.name,
                payment.plan == null ? "-" : payment.plan.name,
                date(payment.createdAt),
                date(payment.verifiedAt)
        ), List.of(
                List.of(button("Valider", confirmCallback("verify_payment:" + payment.id)), button("Refuser", confirmCallback("reject_payment:" + payment.id))),
                List.of(button("Facture", confirmCallback("invoice_for_payment:" + payment.id)))
        ));
    }

    private Response verifyPayment(String value, UserEntity actor) {
        Long id = requireId(value, "paymentId");
        PaymentTransaction payment = billing.verifyPayment(actor, id);
        return text("Paiement valide: " + payment.paymentReference);
    }

    private Response rejectPayment(String value, UserEntity actor) {
        List<String> parts = splitPipe(value);
        Long id = parseLong(parts.get(0), "paymentId");
        String reason = parts.size() > 1 ? parts.get(1) : "Rejete depuis Telegram";
        PaymentTransaction payment = billing.rejectPayment(actor, id, reason);
        return text("Paiement refuse: " + payment.paymentReference);
    }

    private Response expiringSubscriptions(String value) {
        int days = value == null || value.isBlank() ? 7 : positiveInt(value, "jours");
        Instant limit = Instant.now().plus(Duration.ofDays(days));
        List<Subscription> values = subscriptions.findByStatusIn(List.of(
                        Enums.SubscriptionStatus.ACTIVE,
                        Enums.SubscriptionStatus.TRIALING,
                        Enums.SubscriptionStatus.PAST_DUE
                )).stream()
                .filter(subscription -> subscription.currentPeriodEnd != null
                        && !subscription.currentPeriodEnd.isAfter(limit))
                .sorted(Comparator.comparing(subscription -> subscription.currentPeriodEnd))
                .limit(LIST_LIMIT)
                .toList();
        if (values.isEmpty()) {
            return text("Aucun abonnement n'expire dans les " + days + " jours.");
        }
        return text("Abonnements expirant bientot\n\n" + values.stream()
                .map(subscription -> "#%d %s\n%s, fin %s"
                        .formatted(
                                subscription.id,
                                subscription.organization == null ? "-" : subscription.organization.name,
                                subscription.status,
                                date(subscription.currentPeriodEnd)
                        ))
                .collect(Collectors.joining("\n\n")));
    }

    private Response invoiceForPayment(String value, UserEntity actor) {
        PaymentTransaction payment = payments.findById(requireId(value, "paymentId"))
                .orElseThrow(() -> ApiException.notFound("Paiement introuvable"));
        Invoice invoice = invoiceService.createForPayment(payment);
        audit.log(actor, "telegram.invoice.generated", "Invoice", invoice.id, invoice.invoiceNumber);
        return text("Facture generee: #" + invoice.id + " " + invoice.invoiceNumber);
    }

    private Response resendInvoice(String value, UserEntity actor) {
        Invoice invoice = invoiceService.resend(actor, requireId(value, "invoiceId"));
        return text("Facture renvoyee: " + invoice.invoiceNumber);
    }

    private Response openTickets() {
        List<SupportTicket> values = tickets.findByStatusInOrderByUpdatedAtDesc(List.of(
                Enums.TicketStatus.OPEN,
                Enums.TicketStatus.PENDING
        ));
        if (values.isEmpty()) {
            return text("Aucun ticket ouvert.");
        }
        List<List<Button>> keyboard = values.stream().limit(5)
                .map(ticket -> List.of(
                        button("Repondu #" + ticket.id, "answer_ticket:" + ticket.id),
                        button("Fermer #" + ticket.id, confirmCallback("close_ticket:" + ticket.id))
                ))
                .toList();
        return text("Tickets ouverts\n\n" + values.stream()
                .limit(LIST_LIMIT)
                .map(this::ticketLine)
                .collect(Collectors.joining("\n\n")), keyboard);
    }

    private Response urgentTickets() {
        List<SupportTicket> values = tickets.findByStatusInOrderByUpdatedAtDesc(List.of(
                        Enums.TicketStatus.OPEN,
                        Enums.TicketStatus.PENDING
                )).stream()
                .filter(ticket -> ticket.priority == Enums.TicketPriority.HIGH || ticket.priority == Enums.TicketPriority.URGENT)
                .limit(LIST_LIMIT)
                .toList();
        if (values.isEmpty()) {
            return text("Aucun ticket urgent ouvert.");
        }
        return text("Tickets urgents\n\n" + values.stream()
                .map(this::ticketLine)
                .collect(Collectors.joining("\n\n")));
    }

    private Response replyTicket(String value, UserEntity actor) {
        List<String> parts = splitPipe(value);
        if (parts.size() < 2) {
            throw new IllegalArgumentException("/reply_ticket ticketId | message");
        }
        support.reply(actor, parseLong(parts.get(0), "ticketId"), parts.get(1), false);
        return text("Reponse envoyee sur le ticket #" + parts.get(0));
    }

    private Response closeTicket(String value, UserEntity actor) {
        Long id = requireId(value, "ticketId");
        support.changeStatus(actor, id, Enums.TicketStatus.CLOSED);
        return text("Ticket ferme: #" + id);
    }

    private Response answerTicket(String value, UserEntity actor) {
        Long id = requireId(value, "ticketId");
        support.changeStatus(actor, id, Enums.TicketStatus.ANSWERED);
        return text("Ticket marque comme repondu: #" + id);
    }

    private Response assignTicket(String value, UserEntity actor) {
        String[] parts = value == null ? new String[0] : value.strip().split("\\s+", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("/assign_ticket ticketId userId");
        }
        SupportTicket ticket = support.assign(actor, parseLong(parts[0], "ticketId"), parseLong(parts[1], "userId"));
        return text("Ticket #" + ticket.id + " assigne a #" + ticket.assignedTo.id);
    }

    private Response smtpTest(String value, UserEntity actor) {
        String email = require(value, "email");
        var result = mail.test(email);
        audit.log(actor, "telegram.integration.smtp.test", "Integration", null, result.success() ? "success" : "failed");
        return text(result.message());
    }

    private Response telegramTest(UserEntity actor) {
        var result = telegram.test();
        audit.log(actor, "telegram.integration.telegram.test", "Integration", null, result.success() ? "success" : "failed");
        return text(result.message());
    }

    private Response pendingAddons() {
        List<CommunityAddon> values = addonRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(addon -> addon.status == Enums.AddonStatus.PENDING)
                .limit(LIST_LIMIT)
                .toList();
        if (values.isEmpty()) {
            return text("Aucun add-on en attente.");
        }
        List<List<Button>> keyboard = values.stream().limit(5)
                .map(addon -> List.of(
                        button("Approuver #" + addon.id, confirmCallback("approve_addon:" + addon.id)),
                        button("Desactiver #" + addon.id, confirmCallback("disable_addon:" + addon.id))
                ))
                .toList();
        return text("Add-ons en attente\n\n" + values.stream()
                .map(addon -> "#%d %s\n%s, %s"
                        .formatted(addon.id, addon.name, addon.privateUse ? "prive" : "public", addon.manifestUrl))
                .collect(Collectors.joining("\n\n")), keyboard);
    }

    private Response approveAddon(String value, UserEntity actor) {
        CommunityAddon addon = addons.approve(requireId(value, "addonId"), actor);
        audit.log(actor, "telegram.addon.approved", "CommunityAddon", addon.id, addon.addonKey);
        return text("Add-on approuve: #" + addon.id + " " + addon.name);
    }

    private Response disableAddon(String value, UserEntity actor) {
        CommunityAddon addon = addons.disable(requireId(value, "addonId"), actor);
        audit.log(actor, "telegram.addon.disabled", "CommunityAddon", addon.id, addon.addonKey);
        return text("Add-on desactive: #" + addon.id + " " + addon.name);
    }

    private Response addons() {
        UserEntity actor = adminActor();
        List<Map<String, Object>> values = addons.list(actor).stream()
                .limit(LIST_LIMIT)
                .toList();
        if (values.isEmpty()) {
            return text("Aucun add-on installe.");
        }
        return text("Add-ons\n\n" + values.stream()
                .map(addon -> {
                    List<?> allowed = addon.get("allowedUserIds") instanceof List<?> list ? list : List.of();
                    return "#%s %s [%s]\n%s, users assignes: %d"
                            .formatted(
                                    addon.get("id"),
                                    addon.get("name"),
                                    addon.get("status"),
                                    Boolean.TRUE.equals(addon.get("privateUse")) ? "prive" : "public",
                                    allowed.size()
                            );
                })
                .collect(Collectors.joining("\n\n")));
    }

    private Response users(String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<UserEntity> values = users.findByActive(true).stream()
                .filter(user -> needle.isBlank()
                        || contains(user.name, needle)
                        || contains(user.email, needle)
                        || String.valueOf(user.id).equals(needle))
                .limit(LIST_LIMIT)
                .toList();
        if (values.isEmpty()) {
            return text("Aucun utilisateur actif trouve.");
        }
        return text("Utilisateurs actifs\n\n" + values.stream()
                .map(user -> "#%d %s\n%s [%s]".formatted(user.id, user.name, user.email, user.role))
                .collect(Collectors.joining("\n\n")), values.stream()
                .limit(5)
                .map(user -> List.of(button("Fiche client #" + user.id, "client:" + user.id)))
                .toList());
    }

    private Response assignAddon(String value, UserEntity actor) {
        String[] tokens = value == null ? new String[0] : value.strip().split("\\s+", 2);
        if (tokens.length < 2) {
            throw new IllegalArgumentException("/assign_addon addonId userId,userId");
        }
        Long addonId = parseLong(tokens[0], "addonId");
        List<Long> userIds = parseUserIds(tokens[1]);
        CommunityAddon addon = addons.updateAccess(addonId, userIds, actor);
        audit.log(actor, "telegram.addon.access.updated", "CommunityAddon", addon.id, String.valueOf(userIds));
        return text("Acces add-on mis a jour: #%d %s\nUtilisateurs assignes: %s"
                .formatted(addon.id, addon.name, addon.allowedUsers.stream()
                        .map(user -> "#%d %s".formatted(user.id, user.email))
                        .collect(Collectors.joining(", "))));
    }

    private Response addM3u(String text, UserEntity actor) {
        List<String> parts = splitPipe(text);
        if (parts.size() < 2) {
            throw new IllegalArgumentException("/add_m3u Nom | URL playlist");
        }
        IptvAccount account = catalog.saveAccount(
                null,
                require(parts.get(0), "nom"),
                Enums.IptvAccountType.M3U,
                null,
                null,
                null,
                require(parts.get(1), "URL playlist"),
                null,
                true,
                null
        );
        trySync(account.id);
        audit.log(actor, "telegram.iptv.account.created", "IptvAccount", account.id, "M3U " + account.name);
        return text("Compte M3U ajoute: #%d %s\nSante: %s\nCapacite: %s"
                .formatted(account.id, account.name, account.lastHealthStatus, capacityLabel(account)));
    }

    private Response addXtream(String text, UserEntity actor) {
        List<String> parts = splitPipe(text);
        if (parts.size() < 4) {
            throw new IllegalArgumentException("/add_xtream Nom | URL base | username | password");
        }
        IptvAccount account = catalog.saveAccount(
                null,
                require(parts.get(0), "nom"),
                Enums.IptvAccountType.XTREAM,
                require(parts.get(1), "URL base"),
                require(parts.get(2), "username"),
                require(parts.get(3), "password"),
                null,
                null,
                true,
                null
        );
        trySync(account.id);
        IptvAccount refreshed = accountById(account.id);
        audit.log(actor, "telegram.iptv.account.created", "IptvAccount", refreshed.id, "XTREAM " + refreshed.name);
        return text("Compte XTREAM ajoute: #%d %s\nSante: %s\nCapacite detectee: %s"
                .formatted(refreshed.id, refreshed.name, refreshed.lastHealthStatus, capacityLabel(refreshed)));
    }

    private Response mapStatus(String title, Map<String, Object> status) {
        return text(title + "\n\n" + mapLines(status));
    }

    private Response confirm(String message, String action) {
        return text(message, List.of(List.of(
                button("Confirmer", confirmCallback(action)),
                button("Annuler", "cancel")
        )));
    }

    private String confirmCallback(String action) {
        long expiresAt = Instant.now().plus(CONFIRMATION_TTL).getEpochSecond();
        return "confirm:" + expiresAt + ":" + action;
    }

    private Response safe(Command command, UserEntity actor, String action) {
        try {
            Response response = command.run();
            return response == null ? text("OK") : response;
        } catch (ApiException exception) {
            audit.log(actor, "telegram.command.failed", "Telegram", null, action + " " + exception.code());
            return text("Action refusee: " + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            audit.log(actor, "telegram.command.invalid", "Telegram", null, action + " " + exception.getMessage());
            return text("Format invalide: " + exception.getMessage());
        } catch (RuntimeException exception) {
            audit.log(actor, "telegram.command.error", "Telegram", null, action + " " + rootMessage(exception));
            return text("Erreur: " + rootMessage(exception));
        }
    }

    private List<IptvAccount> visibleAccounts() {
        return accounts.findAll().stream()
                .filter(account -> !IptvCatalogService.ARCHIVED_BY_ADMIN.equals(account.disabledReason))
                .sorted(Comparator.comparing(account -> account.id == null ? 0L : account.id))
                .toList();
    }

    private IptvAccount accountById(Long id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
    }

    private UserEntity userById(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
    }

    private UserEntity findUser(String value) {
        String query = require(value, "email ou userId");
        if (query.matches("\\d+")) {
            return userById(Long.parseLong(query));
        }
        return users.findByEmailIgnoreCase(query)
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
    }

    private UserEntity adminActor() {
        return users.findByActive(true).stream()
                .filter(user -> user.role == Enums.UserRole.SUPER_ADMIN)
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("Aucun super administrateur actif pour executer l'action"));
    }

    private List<UserSession> staleSessionList() {
        Instant threshold = Instant.now().minus(STALE_SESSION_AGE);
        return sessions.findByStatusAndLastHeartbeatAtBefore(Enums.SessionStatus.ACTIVE, threshold);
    }

    private boolean isWriteCallback(String value) {
        String action = value.startsWith("do:") ? value.substring(3) : value;
        ConfirmedAction confirmed = confirmedAction(action);
        action = confirmed.action();
        String name = action.split(":", 2)[0];
        return Set.of(
                "clear_cache",
                "sync_limits",
                "disable_account",
                "enable_account",
                "close_session",
                "suspend_user",
                "reactivate_user",
                "verify_payment",
                "reject_payment",
                "invoice_for_payment",
                "close_ticket",
                "answer_ticket",
                "approve_addon",
                "disable_addon"
        ).contains(name);
    }

    private boolean allowedForRole(Enums.UserRole role, String command) {
        Enums.UserRole effective = role == null ? Enums.UserRole.SUPER_ADMIN : role;
        if (effective == Enums.UserRole.SUPER_ADMIN || effective == Enums.UserRole.ADMIN) {
            return true;
        }
        if (Set.of("/admin", "/help", "/start", "/whoami", "/admin_status", "/status").contains(command)) {
            return true;
        }
        return switch (effective) {
            case OPS -> command.startsWith("/account_") || Set.of(
                    "/health", "/sessions", "/capacity", "/accounts", "/iptv", "/account", "/test_account",
                    "/clear_cache", "/sync_limits", "/enable_account", "/disable_account", "/accounts_warning",
                    "/active_sessions", "/close_session", "/stale_sessions", "/cleanup_sessions",
                    "/smtp_status", "/telegram_status", "/telegram_test", "/torbox_status"
            ).contains(command);
            case BILLING -> Set.of(
                    "/users", "/client", "/pending_payments", "/verify_payment", "/reject_payment",
                    "/expiring_subscriptions", "/invoice_for_payment", "/resend_invoice", "/telegram_status"
            ).contains(command);
            case SUPPORT -> Set.of(
                    "/users", "/client", "/categories", "/tickets", "/urgent_tickets",
                    "/reply_ticket", "/close_ticket", "/answer_ticket", "/assign_ticket", "/telegram_status"
            ).contains(command);
            default -> false;
        };
    }

    private String commandForCallback(String value) {
        String action = value == null ? "" : value.strip();
        if (action.startsWith("do:")) {
            action = action.substring(3);
        }
        action = confirmedAction(action).action();
        String name = action.split(":", 2)[0];
        return switch (name) {
            case "account", "test_account", "clear_cache", "sync_limits", "disable_account", "enable_account" -> "/" + name;
            case "close_session" -> "/close_session";
            case "client", "suspend_user", "reactivate_user" -> "/" + name;
            case "payment" -> "/pending_payments";
            case "verify_payment", "reject_payment", "invoice_for_payment" -> "/" + name;
            case "close_ticket", "answer_ticket" -> "/" + name;
            case "approve_addon", "disable_addon" -> "/" + name;
            default -> "/admin";
        };
    }

    private ConfirmedAction confirmedAction(String raw) {
        String value = raw == null ? "" : raw.strip();
        String[] parts = value.split(":", 2);
        if (parts.length == 2 && parts[0].matches("\\d+")) {
            long expiresAt = Long.parseLong(parts[0]);
            return new ConfirmedAction(expiresAt, parts[1], Instant.now().getEpochSecond() > expiresAt);
        }
        if (isWriteActionName(value.split(":", 2)[0])) {
            return new ConfirmedAction(0, value, true);
        }
        return new ConfirmedAction(Long.MAX_VALUE, value, false);
    }

    private boolean isWriteActionName(String name) {
        return Set.of(
                "disable_account",
                "close_session",
                "suspend_user",
                "verify_payment",
                "reject_payment",
                "invoice_for_payment",
                "close_ticket",
                "approve_addon",
                "disable_addon"
        ).contains(name);
    }

    private String accountLine(IptvAccount account) {
        return "#%d %s [%s]\n%s, %d/%s flux"
                .formatted(
                        account.id,
                        account.name,
                        account.accountType,
                        catalog.health(account),
                        account.activeStreams,
                        account.maxStreams <= 0 ? "infini" : String.valueOf(account.maxStreams)
                );
    }

    private String sessionLine(UserSession session) {
        return "#%d %s\nuser #%s, compte #%s, %s, heartbeat %s"
                .formatted(
                        session.id,
                        session.itemId,
                        session.user == null ? "-" : session.user.id,
                        session.iptvAccount == null ? "-" : session.iptvAccount.id,
                        session.status,
                        date(session.lastHeartbeatAt)
                );
    }

    private String paymentLine(PaymentTransaction payment) {
        return "#%d %s\n%s %s, %s, user #%s"
                .formatted(
                        payment.id,
                        payment.paymentReference,
                        payment.amount,
                        payment.currency,
                        payment.status,
                        payment.user == null ? "-" : payment.user.id
                );
    }

    private String ticketLine(SupportTicket ticket) {
        return "#%d %s\n%s, %s, user #%s, assigne #%s"
                .formatted(
                        ticket.id,
                        ticket.subject,
                        ticket.priority,
                        ticket.status,
                        ticket.user == null ? "-" : ticket.user.id,
                        ticket.assignedTo == null ? "-" : ticket.assignedTo.id
                );
    }

    private String sourceUrl(IptvAccount account) {
        String value = account.accountType == Enums.IptvAccountType.M3U ? account.playlistUrl : account.baseUrl;
        if (value == null) {
            return "-";
        }
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }

    private boolean expiresSoon(IptvAccount account) {
        return account.expiresAt != null && !account.expiresAt.isAfter(Instant.now().plus(Duration.ofDays(7)));
    }

    private String capacityLabel(IptvAccount account) {
        return account.maxStreams <= 0 ? "illimitee/inconnue" : account.maxStreams + " flux";
    }

    private String mapLines(Map<String, Object> map) {
        return map.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    private void trySync(Long accountId) {
        try {
            catalog.syncLimits(accountId);
        } catch (RuntimeException ignored) {
            // The account is still saved; operators can retry once the provider responds.
        }
    }

    private String rejectPayload(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("/reject_payment paymentId | raison");
        }
        return value.replace(":", " ");
    }

    private Long requireId(String value, String label) {
        return parseLong(require(value, label), label);
    }

    private List<Long> parseUserIds(String raw) {
        Set<Long> ids = Arrays.stream(raw.split("[,\\s]+"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> parseLong(value, "userId"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ajoutez au moins un userId");
        }
        return List.copyOf(ids);
    }

    private Long parseLong(String value, String label) {
        try {
            return Long.parseLong(value.strip());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " doit etre numerique");
        }
    }

    private int positiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " doit etre un entier positif");
        }
    }

    private List<String> splitPipe(String text) {
        return Arrays.stream(String.valueOf(text == null ? "" : text).split("\\|"))
                .map(String::strip)
                .toList();
    }

    private String command(String text) {
        String first = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int botSuffix = first.indexOf('@');
        return botSuffix >= 0 ? first.substring(0, botSuffix) : first;
    }

    private String arguments(String text) {
        String[] parts = text.split("\\s+", 2);
        return parts.length == 2 ? parts[1].strip() : "";
    }

    private String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " obligatoire");
        }
        return value.strip();
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String date(Instant instant) {
        return instant == null ? "-" : DATE.format(instant);
    }

    private String duration(long seconds) {
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        return "%dj %02dh %02dm".formatted(days, hours, minutes);
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private Button button(String text, String callbackData) {
        return new Button(text, callbackData);
    }

    private Response text(String text) {
        return new Response(text, List.of());
    }

    private Response text(String text, List<List<Button>> keyboard) {
        return new Response(text, keyboard == null ? List.of() : keyboard);
    }

    private interface Command {
        Response run();
    }

    public record Button(String text, String callbackData) {
    }

    public record Response(String text, List<List<Button>> keyboard) {
    }

    public record ChatContext(String chatId, Enums.UserRole role, boolean readOnly, Map<String, Object> adminStatus) {
    }

    private record ConfirmedAction(long expiresAtEpochSecond, String action, boolean expired) {
    }
}
