package com.iptv.saas.repository;

import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.OrganizationMembership;
import com.iptv.saas.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, Long> {
    List<OrganizationMembership> findByOrganization(Organization organization);

    List<OrganizationMembership> findByUser(UserEntity user);

    Optional<OrganizationMembership> findByOrganizationAndUser(Organization organization, UserEntity user);

    boolean existsByOrganizationAndUser(Organization organization, UserEntity user);

    void deleteByOrganizationAndUser(Organization organization, UserEntity user);

    void deleteByUser(UserEntity user);
}
