package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.SupportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support/tickets")
public class SupportController {
    private final SupportService support;

    public SupportController(SupportService support) {
        this.support = support;
    }

    @GetMapping
    public Object list() {
        return Responses.ok(support.listForUser(SecurityUtils.currentUser()).stream().map(ApiMappers::ticket).toList());
    }

    @PostMapping
    public Object create(@Valid @RequestBody TicketRequest request) {
        return Responses.ok(ApiMappers.ticket(support.createTicket(
                SecurityUtils.currentUser(),
                request.subject(),
                request.body(),
                request.priority()
        )));
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
        return Responses.ok(ApiMappers.message(support.reply(SecurityUtils.currentUser(), id, request.body(), false)));
    }

    public record TicketRequest(@NotBlank String subject, @NotBlank String body, Enums.TicketPriority priority) {
    }

    public record ReplyRequest(@NotBlank String body) {
    }
}
