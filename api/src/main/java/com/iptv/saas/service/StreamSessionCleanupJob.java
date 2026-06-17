package com.iptv.saas.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StreamSessionCleanupJob {
    private final StreamingService streaming;

    public StreamSessionCleanupJob(StreamingService streaming) {
        this.streaming = streaming;
    }

    @Scheduled(
            initialDelayString = "${app.iptv.session-cleanup-interval-ms:30000}",
            fixedDelayString = "${app.iptv.session-cleanup-interval-ms:30000}"
    )
    public void cleanupInactiveSessions() {
        streaming.cleanupInactive();
    }
}
