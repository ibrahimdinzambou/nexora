package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.InvoiceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @GetMapping
    public Object list() {
        return Responses.ok(invoices.listForUser(SecurityUtils.currentUser()).stream().map(ApiMappers::invoice).toList());
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable Long id) {
        return Responses.ok(ApiMappers.invoice(invoices.getForUser(SecurityUtils.currentUser(), id)));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        var invoice = invoices.markDownloaded(SecurityUtils.currentUser(), id);
        byte[] bytes = invoice.pdfContent == null ? new byte[0] : invoice.pdfContent;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(invoice.invoiceNumber + ".pdf")
                        .build()
                        .toString())
                .body(bytes);
    }

    @PostMapping("/{id}/resend")
    public Object resend(@PathVariable Long id) {
        return Responses.ok(ApiMappers.invoice(invoices.resend(SecurityUtils.currentUser(), id)));
    }
}
