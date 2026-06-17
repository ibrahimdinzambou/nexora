package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "invoices")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Invoice extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    public PaymentTransaction paymentTransaction;

    @Column(nullable = false, unique = true)
    public String invoiceNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false)
    public String currency = "XOF";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.InvoiceStatus status = Enums.InvoiceStatus.ISSUED;

    public Instant issuedAt;
    public Instant sentAt;
    public Instant downloadedAt;

    @JsonIgnore
    @Lob
    @Column(name = "pdf_content", columnDefinition = "bytea")
    public byte[] pdfContent;
}
