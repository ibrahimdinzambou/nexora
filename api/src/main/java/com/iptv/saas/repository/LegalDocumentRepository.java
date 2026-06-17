package com.iptv.saas.repository;

import com.iptv.saas.domain.LegalDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {
    Optional<LegalDocument> findByDocumentType(String documentType);
}
