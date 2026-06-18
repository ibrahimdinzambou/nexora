package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.SupportTicket;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.SupportMessageRepository;
import com.iptv.saas.repository.SupportTicketRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportServiceTests {
    @Test
    void unassignsATicketWhenNoOperatorIsSelected() {
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        SupportService service = service(tickets, mock(UserRepository.class));
        UserEntity admin = user(1L, Enums.UserRole.ADMIN);
        SupportTicket ticket = new SupportTicket();
        ticket.id = 10L;
        ticket.assignedTo = user(2L, Enums.UserRole.SUPPORT);
        when(tickets.findById(10L)).thenReturn(Optional.of(ticket));
        when(tickets.save(ticket)).thenReturn(ticket);

        service.assign(admin, 10L, null);

        assertNull(ticket.assignedTo);
        verify(tickets).save(ticket);
    }

    @Test
    void refusesToAssignATicketToAClientUser() {
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        UserRepository users = mock(UserRepository.class);
        SupportService service = service(tickets, users);
        UserEntity admin = user(1L, Enums.UserRole.ADMIN);
        UserEntity client = user(3L, Enums.UserRole.USER);
        SupportTicket ticket = new SupportTicket();
        ticket.id = 11L;
        when(tickets.findById(11L)).thenReturn(Optional.of(ticket));
        when(users.findById(3L)).thenReturn(Optional.of(client));

        assertThrows(ApiException.class, () -> service.assign(admin, 11L, 3L));
    }

    private SupportService service(SupportTicketRepository tickets, UserRepository users) {
        return new SupportService(
                tickets,
                mock(SupportMessageRepository.class),
                users,
                mock(OrganizationService.class),
                mock(TransactionalMailService.class),
                mock(EmailTemplateService.class),
                mock(TelegramAlertService.class),
                mock(AuditService.class),
                mock(NotificationService.class)
        );
    }

    private UserEntity user(Long id, Enums.UserRole role) {
        UserEntity user = new UserEntity();
        user.id = id;
        user.role = role;
        return user;
    }
}
