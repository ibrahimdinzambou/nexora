package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "uptime_checks")
public class UptimeCheck extends BaseEntity {
    @Column(nullable = false)
    public String name;

    @Column(nullable = false, length = 2000)
    public String url;

    @Column(nullable = false)
    public String method = "GET";

    public boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.UptimeStatus lastStatus = Enums.UptimeStatus.UNKNOWN;

    public Instant lastCheckedAt;
    public Long lastLatencyMs;

    @Column(length = 2000)
    public String lastError;
}
