package com.iptv.saas.repository;

import com.iptv.saas.domain.IptvAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IptvAccountRepository extends JpaRepository<IptvAccount, Long> {
    List<IptvAccount> findByActiveTrueAndDisabledFalse();

    long countByActiveTrue();

    long countByDisabledFalseAndActiveTrue();
}
