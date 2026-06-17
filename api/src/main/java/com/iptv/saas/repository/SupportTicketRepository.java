package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findByOrganizationOrderByCreatedAtDesc(Organization organization);

    List<SupportTicket> findByStatusInOrderByUpdatedAtDesc(Collection<Enums.TicketStatus> statuses);

    List<SupportTicket> findTop10ByOrderByUpdatedAtDesc();

    long countByStatus(Enums.TicketStatus status);
}
