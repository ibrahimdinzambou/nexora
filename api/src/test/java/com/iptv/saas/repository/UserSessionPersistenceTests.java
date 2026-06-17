package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class UserSessionPersistenceTests {
    @Autowired
    UserSessionRepository sessions;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void persistsLongCommunityAddonItemIdentifiers() {
        String longItemId = "addon~33~movie~catalog~" + "x".repeat(700);
        UserSession session = new UserSession();
        session.sessionToken = UUID.randomUUID().toString();
        session.contentType = "movie";
        session.itemId = longItemId;
        session.streamUrl = "https://example.test/video.mp4";
        session.status = Enums.SessionStatus.ACTIVE;
        session.openedAt = Instant.now();
        session.lastHeartbeatAt = Instant.now();

        Long id = sessions.saveAndFlush(session).id;
        entityManager.clear();

        assertEquals(longItemId, sessions.findById(id).orElseThrow().itemId);
        assertEquals(8192L, jdbc.queryForObject("""
                select character_maximum_length
                from information_schema.columns
                where lower(table_name) = 'user_sessions' and lower(column_name) = 'item_id'
                """, Long.class));
    }
}
