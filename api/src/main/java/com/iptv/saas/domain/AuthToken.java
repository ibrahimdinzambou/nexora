package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "auth_tokens", indexes = {
        @Index(name = "idx_auth_tokens_hash", columnList = "token_hash", unique = true)
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AuthToken extends BaseEntity {
    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    public String tokenHash;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity user;

    @Column(nullable = false)
    public String name = "api";

    public Instant expiresAt;
    public Instant revokedAt;
}
