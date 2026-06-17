package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true)
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class UserEntity extends BaseEntity {
    @Column(nullable = false)
    public String name;

    @Column(nullable = false, unique = true)
    public String email;

    @JsonIgnore
    @Column(nullable = false)
    public String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.UserRole role = Enums.UserRole.USER;

    public boolean active = true;
    public boolean emailVerified = false;
    public boolean twoFactorEnabled = false;

    @JsonIgnore
    public String emailOtp;

    @JsonIgnore
    public Instant emailOtpExpiresAt;

    @JsonIgnore
    public String resetOtp;

    @JsonIgnore
    public Instant resetOtpExpiresAt;

    @JsonIgnore
    public String twoFactorCode;

    @JsonIgnore
    public Instant twoFactorCodeExpiresAt;

    @ManyToOne(fetch = FetchType.EAGER)
    public Organization currentOrganization;

    @Column(length = 2000)
    public String allowedCategories = "[]";
}
