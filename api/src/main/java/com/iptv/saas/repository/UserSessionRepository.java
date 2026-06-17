package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findBySessionToken(String sessionToken);

    List<UserSession> findByStatus(Enums.SessionStatus status);

    List<UserSession> findByOrganizationAndStatus(Organization organization, Enums.SessionStatus status);

    List<UserSession> findByUserAndStatus(UserEntity user, Enums.SessionStatus status);

    List<UserSession> findByIptvAccountAndStatus(IptvAccount account, Enums.SessionStatus status);

    List<UserSession> findByOrganizationAndStatusAndLastHeartbeatAtBefore(
            Organization organization,
            Enums.SessionStatus status,
            Instant threshold
    );

    List<UserSession> findByStatusAndLastHeartbeatAtBefore(Enums.SessionStatus status, Instant threshold);

    long countByStatus(Enums.SessionStatus status);
}
