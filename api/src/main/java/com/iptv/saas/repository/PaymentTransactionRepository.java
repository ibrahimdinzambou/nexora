package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByPaymentReference(String paymentReference);

    List<PaymentTransaction> findByOrganizationOrderByCreatedAtDesc(Organization organization);

    List<PaymentTransaction> findByStatusOrderByCreatedAtDesc(Enums.PaymentStatus status);

    List<PaymentTransaction> findTop10ByOrderByCreatedAtDesc();

    long countByStatus(Enums.PaymentStatus status);
}
