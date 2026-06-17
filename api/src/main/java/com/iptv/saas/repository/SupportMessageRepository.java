package com.iptv.saas.repository;

import com.iptv.saas.domain.SupportMessage;
import com.iptv.saas.domain.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
    List<SupportMessage> findByTicketOrderByCreatedAtAsc(SupportTicket ticket);

    List<SupportMessage> findByTicketAndInternalMessageFalseOrderByCreatedAtAsc(SupportTicket ticket);
}
