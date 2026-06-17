package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "plans", indexes = {
        @Index(name = "idx_plans_code", columnList = "code", unique = true)
})
public class Plan extends BaseEntity {
    @Column(nullable = false, unique = true)
    public String code;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal priceMonthly = BigDecimal.ZERO;

    @Column(nullable = false)
    public String currency = "XAF";

    @Column(length = 1000)
    public String description = "";

    @Column(length = 160)
    public String highlight = "";

    public int trialDays = 7;
    public Integer billingPeriodDays = 30;
    public int maxUsers = 1;
    public int maxIptvAccounts = 1;
    public int maxConcurrentStreams = 1;
    public int storageGb = 1;
    public boolean active = true;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("priority ASC, id ASC")
    public List<PlanEntitlement> entitlements = new ArrayList<>();
}
