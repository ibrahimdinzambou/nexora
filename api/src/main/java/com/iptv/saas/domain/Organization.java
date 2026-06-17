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

@Entity
@Table(name = "organizations", indexes = {
        @Index(name = "idx_organizations_slug", columnList = "slug", unique = true)
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Organization extends BaseEntity {
    @Column(nullable = false)
    public String name;

    @Column(nullable = false, unique = true)
    public String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.OrganizationStatus status = Enums.OrganizationStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity owner;

    public String billingEmail;
}
