package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_user", indexes = {
        @Index(name = "idx_org_user_unique", columnList = "organization_id,user_id", unique = true)
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class OrganizationMembership extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity user;

    @Column(nullable = false)
    public String role = "member";

    @Column(nullable = false)
    public String status = "active";
}
