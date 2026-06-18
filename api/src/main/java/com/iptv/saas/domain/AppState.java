package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_state", indexes = {
        @Index(name = "idx_app_state_key", columnList = "state_key", unique = true)
})
public class AppState extends BaseEntity {
    @Column(name = "state_key", nullable = false, unique = true, length = 120)
    public String stateKey;

    @Column(name = "state_value", nullable = false, length = 4000)
    public String stateValue = "";
}
