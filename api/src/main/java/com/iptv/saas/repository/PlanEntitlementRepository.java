package com.iptv.saas.repository;

import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.PlanEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, Long> {
    List<PlanEntitlement> findByPlanOrderByPriorityAscIdAsc(Plan plan);
}
