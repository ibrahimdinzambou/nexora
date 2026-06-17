package com.iptv.saas.service;

import com.iptv.saas.domain.Invoice;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

@Service
public class InvoicePdfService {
    private final EmailTemplateService templates;

    public InvoicePdfService(EmailTemplateService templates) {
        this.templates = templates;
    }

    public byte[] render(Invoice invoice) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(templates.invoice(invoice), null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Impossible de générer la facture PDF", exception);
        }
    }
}
