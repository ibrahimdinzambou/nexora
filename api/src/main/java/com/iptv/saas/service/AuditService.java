package com.iptv.saas.service;

import com.iptv.saas.domain.AuditLog;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditLogRepository logs;

    public AuditService(AuditLogRepository logs) {
        this.logs = logs;
    }

    @Transactional
    public void log(UserEntity user, String action, String subjectType, Long subjectId, String metadata) {
        AuditLog log = new AuditLog();
        log.user = user;
        log.action = action;
        log.subjectType = subjectType;
        log.subjectId = subjectId;
        log.metadata = metadata;
        logs.save(log);
    }
}
