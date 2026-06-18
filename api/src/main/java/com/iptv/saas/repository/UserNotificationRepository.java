package com.iptv.saas.repository;

import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    List<UserNotification> findTop20ByUserOrderByPublishedAtDescIdDesc(UserEntity user);

    long countByUserAndReadAtIsNull(UserEntity user);

    Optional<UserNotification> findByUserAndId(UserEntity user, Long id);

    List<UserNotification> findByUserAndReadAtIsNull(UserEntity user);
}
