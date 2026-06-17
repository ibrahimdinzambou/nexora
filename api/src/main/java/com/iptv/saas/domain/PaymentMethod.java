package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_methods", indexes = {
        @Index(name = "idx_payment_methods_code", columnList = "code", unique = true)
})
public class PaymentMethod extends BaseEntity {
    @Column(nullable = false, unique = true)
    public String code;

    @Column(nullable = false)
    public String name;

    @Column(length = 4000)
    public String instructions;

    public boolean active = true;
}
