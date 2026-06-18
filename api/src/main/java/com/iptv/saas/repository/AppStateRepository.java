package com.iptv.saas.repository;

import com.iptv.saas.domain.AppState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppStateRepository extends JpaRepository<AppState, Long> {
    Optional<AppState> findByStateKey(String stateKey);
}
