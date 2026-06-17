package com.iptv.saas.repository;

import com.iptv.saas.domain.AuthToken;
import com.iptv.saas.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    void deleteByUser(UserEntity user);
}
