package com.iptv.saas.repository;

import com.iptv.saas.domain.CommunityAddon;
import com.iptv.saas.domain.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommunityAddonRepository extends JpaRepository<CommunityAddon, Long> {
    Optional<CommunityAddon> findByManifestUrl(String manifestUrl);

    List<CommunityAddon> findByStatusOrderByNameAsc(Enums.AddonStatus status);

    List<CommunityAddon> findAllByOrderByCreatedAtDesc();

    boolean existsByStatus(Enums.AddonStatus status);
}
