package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Invoice;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByOrganizationOrderByIssuedAtDesc(Organization organization);

    Optional<Invoice> findByPaymentTransaction(PaymentTransaction paymentTransaction);

    long countByStatus(Enums.InvoiceStatus status);
}
