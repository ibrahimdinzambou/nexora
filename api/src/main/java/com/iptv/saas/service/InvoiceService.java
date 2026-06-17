package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Invoice;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.InvoiceRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class InvoiceService {
    private final InvoiceRepository invoices;
    private final OrganizationService organizationService;
    private final TransactionalMailService mail;
    private final EmailTemplateService templates;
    private final InvoicePdfService invoicePdf;
    private final AuditService audit;

    public InvoiceService(
            InvoiceRepository invoices,
            OrganizationService organizationService,
            TransactionalMailService mail,
            EmailTemplateService templates,
            InvoicePdfService invoicePdf,
            AuditService audit
    ) {
        this.invoices = invoices;
        this.organizationService = organizationService;
        this.mail = mail;
        this.templates = templates;
        this.invoicePdf = invoicePdf;
        this.audit = audit;
    }

    @Transactional
    public Invoice createForPayment(PaymentTransaction payment) {
        return invoices.findByPaymentTransaction(payment).orElseGet(() -> {
            Invoice invoice = new Invoice();
            invoice.organization = payment.organization;
            invoice.paymentTransaction = payment;
            invoice.invoiceNumber = nextInvoiceNumber(payment.id);
            invoice.amount = payment.amount;
            invoice.currency = payment.currency;
            invoice.status = Enums.InvoiceStatus.ISSUED;
            invoice.issuedAt = Instant.now();
            invoice.pdfContent = invoicePdf.render(invoice);
            return invoices.save(invoice);
        });
    }

    @Transactional(readOnly = true)
    public List<Invoice> listForUser(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return invoices.findByOrganizationOrderByIssuedAtDesc(organization);
    }

    @Transactional(readOnly = true)
    public Invoice getForUser(UserEntity user, Long id) {
        Invoice invoice = invoices.findById(id).orElseThrow(() -> ApiException.notFound("Facture introuvable"));
        if (!SecurityUtils.isAdminLike(user)) {
            Organization organization = organizationService.currentOrganization(user);
            if (invoice.organization == null || !invoice.organization.id.equals(organization.id)) {
                throw ApiException.forbidden("Acces facture refuse");
            }
        }
        return invoice;
    }

    @Transactional
    public Invoice markDownloaded(UserEntity user, Long id) {
        Invoice invoice = getForUser(user, id);
        invoice.status = Enums.InvoiceStatus.DOWNLOADED;
        invoice.downloadedAt = Instant.now();
        invoice.pdfContent = invoicePdf.render(invoice);
        audit.log(user, "invoice.downloaded", "Invoice", invoice.id, invoice.invoiceNumber);
        return invoices.save(invoice);
    }

    @Transactional
    public Invoice resend(UserEntity user, Long id) {
        Invoice invoice = getForUser(user, id);
        invoice.status = Enums.InvoiceStatus.SENT;
        invoice.sentAt = Instant.now();
        invoice.pdfContent = invoicePdf.render(invoice);
        invoices.save(invoice);
        String to = invoice.organization == null ? null : invoice.organization.billingEmail;
        mail.sendHtmlWithAttachment(
                to,
                "Facture " + invoice.invoiceNumber,
                templates.invoice(invoice),
                invoice.invoiceNumber + ".pdf",
                invoice.pdfContent
        );
        audit.log(user, "invoice.resent", "Invoice", invoice.id, invoice.invoiceNumber);
        return invoice;
    }

    private String nextInvoiceNumber(Long paymentId) {
        String date = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
        long suffix = paymentId == null ? System.currentTimeMillis() % 100000 : paymentId;
        return "INV-" + date + "-" + String.format("%05d", suffix);
    }

}
