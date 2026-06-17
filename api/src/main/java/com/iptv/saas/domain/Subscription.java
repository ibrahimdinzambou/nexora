package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "subscriptions")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Subscription extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    public Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.SubscriptionStatus status = Enums.SubscriptionStatus.TRIALING;

    public Instant startedAt;
    public Instant trialEndsAt;
    public Instant currentPeriodEnd;
    public boolean cancelAtPeriodEnd = false;
}
