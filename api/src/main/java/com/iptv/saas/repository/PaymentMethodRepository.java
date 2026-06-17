package com.iptv.saas.repository;

import com.iptv.saas.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
    Optional<PaymentMethod> findByCode(String code);

    List<PaymentMethod> findByActiveTrue();
}
