package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Invoice;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PaymentMethod;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.domain.Plan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceTemplateTests {
    @Test
    void rendersProvidedInvoiceModelAsHtmlAndPdf() {
        EmailTemplateService templates = new EmailTemplateService("Nexora", "billing@nexora.test");
        Invoice invoice = sampleInvoice();

        String html = templates.invoice(invoice);
        byte[] pdf = new InvoicePdfService(templates).render(invoice);

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("FACTURE"));
        assertTrue(html.contains("INV-20260613-0042"));
        assertTrue(html.contains("ACME Media"));
        assertTrue(html.contains("Abonnement Pro"));
        assertTrue(html.contains("PAY-20260613-9042"));
        assertTrue(html.contains("000,00 XAF"));
        assertTrue(pdf.length > 1_000);
        assertTrue(new String(pdf, 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF-"));
    }

    private Invoice sampleInvoice() {
        Organization organization = new Organization();
        organization.name = "ACME Media";
        organization.billingEmail = "finance@acme.test";

        Plan plan = new Plan();
        plan.name = "Pro";
        

        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.name = "Carte bancaire";

        PaymentTransaction payment = new PaymentTransaction();
        payment.paymentReference = "PAY-20260613-9042";
        payment.status = Enums.PaymentStatus.VERIFIED;
        payment.plan = plan;
        payment.paymentMethod = paymentMethod;

        Invoice invoice = new Invoice();
        invoice.invoiceNumber = "INV-20260613-0042";
        invoice.organization = organization;
        invoice.paymentTransaction = payment;
        invoice.amount = new BigDecimal("15000.00");
        invoice.currency = "XAF";
        invoice.issuedAt = Instant.parse("2026-06-13T12:00:00Z");
        return invoice;
    }
}
