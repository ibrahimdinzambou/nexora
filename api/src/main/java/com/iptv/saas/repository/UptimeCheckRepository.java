package com.iptv.saas.repository;

import com.iptv.saas.domain.UptimeCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, Long> {
    List<UptimeCheck> findByEnabledTrue();
}
