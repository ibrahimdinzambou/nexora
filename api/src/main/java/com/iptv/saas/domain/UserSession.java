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

import java.time.Instant;

@Entity
@Table(name = "user_sessions", indexes = {
        @Index(name = "idx_user_sessions_token", columnList = "session_token", unique = true)
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class UserSession extends BaseEntity {
    @Column(name = "session_token", nullable = false, unique = true)
    public String sessionToken;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity user;

    @ManyToOne(fetch = FetchType.EAGER)
    public Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    public IptvAccount iptvAccount;

    @Column(nullable = false)
    public String contentType;

    @Column(nullable = false, length = 8192)
    public String itemId;

    @Column(length = 4000)
    public String streamUrl;

    @Column(length = 16)
    public String playbackQuality = "auto";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.SessionStatus status = Enums.SessionStatus.ACTIVE;

    public Instant openedAt;
    public Instant lastHeartbeatAt;
    public Instant closedAt;
}
