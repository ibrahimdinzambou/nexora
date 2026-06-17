package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.SupportTicketRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.SupportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/support/tickets")
public class AdminSupportController {
    private final SupportTicketRepository tickets;
    private final SupportService support;

    public AdminSupportController(SupportTicketRepository tickets, SupportService support) {
        this.tickets = tickets;
        this.support = support;
    }

    @GetMapping
    public Object list() {
        return Responses.ok(tickets.findAll().stream().map(ApiMappers::ticket).toList());
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable Long id) {
        var user = SecurityUtils.currentUser();
        var ticket = support.getTicket(user, id);
        var body = Responses.map();
        body.put("ticket", ApiMappers.ticket(ticket));
        body.put("messages", support.visibleMessages(user, ticket).stream().map(ApiMappers::message).toList());
        return Responses.ok(body);
    }

    @PostMapping("/{id}/reply")
    public Object reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest request) {
        return Responses.ok(ApiMappers.message(support.reply(SecurityUtils.currentUser(), id, request.body(), request.internal())));
    }

    @PatchMapping("/{id}/assign")
    public Object assign(@PathVariable Long id, @RequestBody AssignRequest request) {
        return Responses.ok(ApiMappers.ticket(support.assign(SecurityUtils.currentUser(), id, request.userId())));
    }

    @PatchMapping("/{id}/status")
    public Object status(@PathVariable Long id, @RequestBody StatusRequest request) {
        return Responses.ok(ApiMappers.ticket(support.changeStatus(SecurityUtils.currentUser(), id, request.status())));
    }

    public record ReplyRequest(@NotBlank String body, boolean internal) {
    }

    public record AssignRequest(Long userId) {
    }

    public record StatusRequest(Enums.TicketStatus status) {
    }
}
