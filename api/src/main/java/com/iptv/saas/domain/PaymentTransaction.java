package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_transactions", indexes = {
        @Index(name = "idx_payments_reference", columnList = "payment_reference", unique = true)
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PaymentTransaction extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity user;

    @ManyToOne(fetch = FetchType.EAGER)
    public Plan plan;

    @ManyToOne(fetch = FetchType.EAGER)
    public PaymentMethod paymentMethod;

    @Column(name = "payment_reference", nullable = false, unique = true)
    public String paymentReference;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false)
    public String currency = "XOF";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.PaymentStatus status = Enums.PaymentStatus.PENDING;

    @Column(length = 2000)
    public String proofUrl;

    @Column(length = 2000)
    public String rejectionReason;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity verifiedBy;

    public Instant expiresAt;
    public Instant verifiedAt;
}
