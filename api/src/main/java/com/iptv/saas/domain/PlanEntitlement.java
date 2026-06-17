package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "plan_entitlements", indexes = {
        @Index(name = "idx_plan_entitlements_plan", columnList = "plan_id")
})
@JsonIgnoreProperties({"plan", "hibernateLazyInitializer", "handler"})
public class PlanEntitlement extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    public Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.PlanEntitlementMode mode = Enums.PlanEntitlementMode.ALL;

    @Column(nullable = false)
    public String contentType = "all";

    @Column(length = 255)
    public String categoryId;

    @Column(length = 255)
    public String keyword;

    @Column(nullable = false, length = 160)
    public String label = "Catalogue complet";

    public boolean enabled = true;
    public int priority = 0;
}
