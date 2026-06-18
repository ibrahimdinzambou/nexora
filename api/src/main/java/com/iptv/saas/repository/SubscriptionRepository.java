package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findFirstByOrganizationOrderByCreatedAtDesc(Organization organization);

    List<Subscription> findByOrganization(Organization organization);

    List<Subscription> findByOrganizationAndStatusIn(Organization organization, Collection<Enums.SubscriptionStatus> statuses);

    boolean existsByOrganizationAndPlanAndTrialEndsAtIsNotNull(Organization organization, Plan plan);

    long countByStatus(Enums.SubscriptionStatus status);

    List<Subscription> findByStatusIn(Collection<Enums.SubscriptionStatus> statuses);
}
