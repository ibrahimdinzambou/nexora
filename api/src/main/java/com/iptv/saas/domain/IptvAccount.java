package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "iptv_accounts")
public class IptvAccount extends BaseEntity {
    @Column(nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.IptvAccountType accountType = Enums.IptvAccountType.XTREAM;

    public String baseUrl;
    public String username;
    public String password;

    @Column(length = 4000)
    public String playlistUrl;

    public boolean active = true;
    public boolean disabled = false;
    public Instant expiresAt;
    public int maxStreams = 1;
    public int activeStreams = 0;
    public int failureCount = 0;
    public String lastHealthStatus = "unknown";
    public String disabledReason;
}
