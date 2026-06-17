package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "legal_documents", indexes = {
        @Index(name = "idx_legal_document_type", columnList = "document_type", unique = true)
})
public class LegalDocument extends BaseEntity {
    @Column(name = "document_type", nullable = false, unique = true)
    public String documentType;

    @Column(nullable = false)
    public String title;

    @Column(nullable = false, length = 20000)
    public String content;

    public boolean published = true;
}
