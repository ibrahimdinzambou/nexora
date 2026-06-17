package com.iptv.saas.web;

import com.iptv.saas.repository.AuditLogRepository;
import com.iptv.saas.repository.UptimeCheckRepository;
import com.iptv.saas.service.OpsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ops")
public class OpsController {
    private final OpsService ops;
    private final AuditLogRepository auditLogs;
    private final UptimeCheckRepository uptimeChecks;

    public OpsController(OpsService ops, AuditLogRepository auditLogs, UptimeCheckRepository uptimeChecks) {
        this.ops = ops;
        this.auditLogs = auditLogs;
        this.uptimeChecks = uptimeChecks;
    }

    @GetMapping("/health")
    public Object health() {
        return Responses.ok(ops.health());
    }

    @GetMapping("/metrics")
    public Object metrics() {
        return Responses.ok(ops.metrics());
    }

    @GetMapping("/audit-logs")
    public Object auditLogs() {
        return Responses.ok(this.auditLogs.findTop100ByOrderByCreatedAtDesc().stream().map(ApiMappers::audit).toList());
    }

    @GetMapping("/uptime-checks")
    public Object uptimeChecks() {
        return Responses.ok(this.uptimeChecks.findAll().stream().map(ApiMappers::uptime).toList());
    }

    @PostMapping("/uptime-checks")
    public Object createCheck(@Valid @RequestBody UptimeRequest request) {
        return Responses.ok(ApiMappers.uptime(ops.saveCheck(
                null,
                request.name(),
                request.url(),
                request.method(),
                request.enabled()
        )));
    }

    @PostMapping("/uptime-checks/{id}/run")
    public Object runCheck(@PathVariable Long id) {
        return Responses.ok(ApiMappers.uptime(ops.runCheck(id)));
    }

    public record UptimeRequest(@NotBlank String name, @NotBlank String url, String method, Boolean enabled) {
    }
}
