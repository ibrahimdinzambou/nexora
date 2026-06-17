package com.iptv.saas.web;

import com.iptv.saas.domain.Invoice;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PaymentMethod;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.AuditService;
import com.iptv.saas.service.EmailTemplateService;
import com.iptv.saas.service.InvoicePdfService;
import com.iptv.saas.service.TelegramAdminBotService;
import com.iptv.saas.service.TelegramAlertService;
import com.iptv.saas.service.TransactionalMailService;
import com.iptv.saas.service.TorBoxTorrentResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/integrations")
public class AdminIntegrationController {
    private final TransactionalMailService mail;
    private final TelegramAlertService telegram;
    private final TelegramAdminBotService telegramAdmin;
    private final EmailTemplateService templates;
    private final InvoicePdfService invoicePdf;
    private final AuditService audit;
    private final TorBoxTorrentResolver torBox;

    public AdminIntegrationController(
            TransactionalMailService mail,
            TelegramAlertService telegram,
            TelegramAdminBotService telegramAdmin,
            EmailTemplateService templates,
            InvoicePdfService invoicePdf,
            AuditService audit,
            TorBoxTorrentResolver torBox
    ) {
        this.mail = mail;
        this.telegram = telegram;
        this.telegramAdmin = telegramAdmin;
        this.templates = templates;
        this.invoicePdf = invoicePdf;
        this.audit = audit;
        this.torBox = torBox;
    }

    @GetMapping("/status")
    public Object status() {
        return Responses.ok(Map.of(
                "smtp", mail.status(),
                "telegram", telegram.status(),
                "telegramAdmin", telegramAdmin.status(),
                "torbox", torBox.status()
        ));
    }

    @PostMapping("/smtp/test")
    public Object testSmtp(@Valid @RequestBody(required = false) SmtpTestRequest request) {
        var actor = SecurityUtils.currentUser();
        String destination = request == null || request.email() == null || request.email().isBlank()
                ? actor.email
                : request.email();
        var result = mail.test(destination);
        audit.log(actor, "integration.smtp.test", "Integration", null, result.success() ? "success" : "failed");
        return Responses.ok(result);
    }

    @PostMapping("/telegram/test")
    public Object testTelegram() {
        var actor = SecurityUtils.currentUser();
        var result = telegram.test();
        audit.log(actor, "integration.telegram.test", "Integration", null, result.success() ? "success" : "failed");
        return Responses.ok(result);
    }

    @PostMapping("/telegram/admin/test")
    public Object testTelegramAdmin() {
        var actor = SecurityUtils.currentUser();
        var result = telegramAdmin.test();
        audit.log(actor, "integration.telegram.admin.test", "Integration", null, result.success() ? "success" : "failed");
        return Responses.ok(result);
    }

    @PostMapping("/templates/test")
    public Object testTemplates(@Valid @RequestBody(required = false) SmtpTestRequest request) {
        var actor = SecurityUtils.currentUser();
        String destination = request == null || request.email() == null || request.email().isBlank()
                ? actor.email
                : request.email();
        Invoice sampleInvoice = new Invoice();
        sampleInvoice.invoiceNumber = "INV-DEMO-" + Instant.now().toEpochMilli();
        sampleInvoice.amount = new BigDecimal("15000.00");
        sampleInvoice.currency = "XOF";
        sampleInvoice.issuedAt = Instant.now();

        Organization organization = new Organization();
        organization.name = "ACME Media";
        organization.billingEmail = "finance@acmemedia.local";
        sampleInvoice.organization = organization;

        PaymentTransaction payment = new PaymentTransaction();
        payment.paymentReference = "PAY-20260613-9042";
        payment.amount = sampleInvoice.amount;
        payment.currency = sampleInvoice.currency;
        payment.status = com.iptv.saas.domain.Enums.PaymentStatus.VERIFIED;

        Plan plan = new Plan();
        plan.name = "Pro";
        payment.plan = plan;

        PaymentMethod method = new PaymentMethod();
        method.name = "Carte bancaire";
        payment.paymentMethod = method;
        sampleInvoice.paymentTransaction = payment;

        String selectedTemplate = request == null ? null : request.template();
        List<TransactionalMailService.DeliveryResult> deliveries = new ArrayList<>();
        if (matchesTemplate(selectedTemplate, "showcase")) {
            deliveries.add(mail.deliverHtml(
                        destination,
                        "Bibliothèque des modèles Nexora",
                        templates.showcase()
            ));
        }
        if (matchesTemplate(selectedTemplate, "security")) {
            deliveries.add(mail.deliverHtml(
                        destination,
                        "Modèle sécurité Nexora",
                        templates.otp(
                                "Validation de votre compte",
                                "Utilisez ce code de démonstration pour valider votre identité.",
                                "482913",
                                10
                        )
            ));
        }
        if (matchesTemplate(selectedTemplate, "support")) {
            deliveries.add(mail.deliverHtml(
                        destination,
                        "Modèle support Nexora",
                        templates.supportOpened(1042L, "Test de qualité du flux principal")
            ));
        }
        if (matchesTemplate(selectedTemplate, "invoice")) {
            deliveries.add(mail.deliverHtmlWithAttachment(
                        destination,
                        "Facture officielle Nexora",
                        templates.invoiceClassic(sampleInvoice),
                        sampleInvoice.invoiceNumber + ".pdf",
                        invoicePdf.render(sampleInvoice)
            ));
        }
        if (matchesTemplate(selectedTemplate, "invoice-modern")) {
            deliveries.add(mail.deliverHtml(
                        destination,
                        "Modèle facture moderne Nexora",
                        templates.invoiceModern(sampleInvoice)
            ));
        }
        if (matchesTemplate(selectedTemplate, "invoice-compact")) {
            deliveries.add(mail.deliverHtml(
                        destination,
                        "Modèle facture compacte Nexora",
                        templates.invoiceCompact(sampleInvoice)
            ));
        }
        long delivered = deliveries.stream().filter(TransactionalMailService.DeliveryResult::success).count();
        boolean success = delivered == deliveries.size();
        audit.log(actor, "integration.templates.test", "Integration", null, success ? "success" : "failed");
        return Responses.ok(Map.of(
                "success", success,
                "message", delivered + "/" + deliveries.size() + " modèles envoyés à " + destination,
                "deliveries", deliveries
        ));
    }

    private boolean matchesTemplate(String selected, String candidate) {
        return selected == null || selected.isBlank() || candidate.equalsIgnoreCase(selected);
    }

    public record SmtpTestRequest(@Email String email, String template) {
    }
}
