package com.iptv.saas.service;

import com.iptv.saas.domain.*;
import com.iptv.saas.repository.SupportMessageRepository;
import com.iptv.saas.repository.SupportTicketRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SupportService {
    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final UserRepository users;
    private final OrganizationService organizationService;
    private final TransactionalMailService mail;
    private final EmailTemplateService templates;
    private final TelegramAlertService telegram;
    private final AuditService audit;
    private final NotificationService notifications;

    public SupportService(
            SupportTicketRepository tickets,
            SupportMessageRepository messages,
            UserRepository users,
            OrganizationService organizationService,
            TransactionalMailService mail,
            EmailTemplateService templates,
            TelegramAlertService telegram,
            AuditService audit,
            NotificationService notifications
    ) {
        this.tickets = tickets;
        this.messages = messages;
        this.users = users;
        this.organizationService = organizationService;
        this.mail = mail;
        this.templates = templates;
        this.telegram = telegram;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listForUser(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return tickets.findByOrganizationOrderByCreatedAtDesc(organization);
    }

    @Transactional
    public SupportTicket createTicket(UserEntity user, String subject, String body, Enums.TicketPriority priority) {
        Organization organization = organizationService.currentOrganization(user);
        SupportTicket ticket = new SupportTicket();
        ticket.organization = organization;
        ticket.user = user;
        ticket.subject = subject;
        ticket.priority = priority == null ? Enums.TicketPriority.NORMAL : priority;
        ticket.status = Enums.TicketStatus.OPEN;
        ticket = tickets.save(ticket);

        SupportMessage message = new SupportMessage();
        message.ticket = ticket;
        message.author = user;
        message.body = body;
        messages.save(message);

        mail.sendHtml(
                user.email,
                "Ticket support #" + ticket.id + " reçu",
                templates.supportOpened(ticket.id, subject)
        );
        if (ticket.priority == Enums.TicketPriority.HIGH || ticket.priority == Enums.TicketPriority.URGENT) {
            telegram.send(
                    "Ticket support prioritaire",
                    "#" + ticket.id + " - " + ticket.priority + " - " + ticket.subject,
                    List.of(List.of(
                            new TelegramAlertService.Action("Repondu #" + ticket.id, "answer_ticket:" + ticket.id),
                            new TelegramAlertService.Action("Fermer #" + ticket.id, "confirm:close_ticket:" + ticket.id)
                    ))
            );
        }
        audit.log(user, "support.ticket.created", "SupportTicket", ticket.id, subject);
        return ticket;
    }

    @Transactional(readOnly = true)
    public SupportTicket getTicket(UserEntity user, Long id) {
        SupportTicket ticket = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket introuvable"));
        if (!SecurityUtils.isAdminLike(user)) {
            Organization organization = organizationService.currentOrganization(user);
            if (ticket.organization == null || !ticket.organization.id.equals(organization.id)) {
                throw ApiException.forbidden("Acces ticket refuse");
            }
        }
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<SupportMessage> visibleMessages(UserEntity user, SupportTicket ticket) {
        return SecurityUtils.isAdminLike(user)
                ? messages.findByTicketOrderByCreatedAtAsc(ticket)
                : messages.findByTicketAndInternalMessageFalseOrderByCreatedAtAsc(ticket);
    }

    @Transactional
    public SupportMessage reply(UserEntity user, Long ticketId, String body, boolean internal) {
        SupportTicket ticket = getTicket(user, ticketId);
        SupportMessage message = new SupportMessage();
        message.ticket = ticket;
        message.author = user;
        message.body = body;
        message.internalMessage = internal && SecurityUtils.isAdminLike(user);
        message = messages.save(message);
        if (SecurityUtils.isAdminLike(user)) {
            ticket.status = message.internalMessage ? Enums.TicketStatus.PENDING : Enums.TicketStatus.ANSWERED;
            if (!message.internalMessage && ticket.user != null) {
                mail.sendHtml(
                        ticket.user.email,
                        "Nouvelle réponse sur le ticket #" + ticket.id,
                        templates.supportReply(ticket.id, ticket.subject)
                );
                notifications.notifyUser(
                        ticket.user,
                        "Nouvelle reponse support",
                        "Le support a repondu au ticket #" + ticket.id + " - " + ticket.subject,
                        "support",
                        "/watch.html"
                );
            }
        } else {
            ticket.status = Enums.TicketStatus.PENDING;
        }
        tickets.save(ticket);
        audit.log(user, "support.ticket.replied", "SupportTicket", ticket.id, ticket.subject);
        return message;
    }

    @Transactional
    public SupportTicket assign(UserEntity admin, Long ticketId, Long assigneeId) {
        SupportTicket ticket = getTicket(admin, ticketId);
        if (assigneeId == null) {
            ticket.assignedTo = null;
            audit.log(admin, "support.ticket.unassigned", "SupportTicket", ticket.id, null);
            return tickets.save(ticket);
        }
        UserEntity assignee = users.findById(assigneeId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (!SecurityUtils.isAdminLike(assignee)) {
            throw ApiException.validation("Le ticket doit être assigné à un opérateur");
        }
        ticket.assignedTo = assignee;
        audit.log(admin, "support.ticket.assigned", "SupportTicket", ticket.id, String.valueOf(assignee.id));
        return tickets.save(ticket);
    }

    @Transactional
    public SupportTicket changeStatus(UserEntity admin, Long ticketId, Enums.TicketStatus status) {
        SupportTicket ticket = getTicket(admin, ticketId);
        ticket.status = status;
        audit.log(admin, "support.ticket.status", "SupportTicket", ticket.id, status.name());
        return tickets.save(ticket);
    }
}
