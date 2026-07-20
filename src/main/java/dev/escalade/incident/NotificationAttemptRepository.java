package dev.escalade.incident;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByIncidentIdOrderByStepOrderAscCreatedAtAsc(UUID incidentId);

    /**
     * Claim a batch of due attempts for this worker. {@code FOR UPDATE SKIP LOCKED} lets several
     * worker instances poll the same table concurrently: each locks a disjoint set and skips rows
     * another worker already holds, so no attempt is processed twice — no external broker required.
     * Must be called within a transaction; locks are held until it commits.
     */
    @Query(value = """
            SELECT * FROM notification_attempt
            WHERE status = 'PENDING' AND due_at <= :now
            ORDER BY due_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationAttempt> claimDueAttempts(@Param("now") Instant now, @Param("limit") int limit);

    /** Halt escalation: cancel any not-yet-sent attempts for an acked/resolved incident. */
    @Modifying
    @Query("update NotificationAttempt a set a.status = dev.escalade.incident.AttemptStatus.CANCELLED "
            + "where a.incidentId = :incidentId and a.status = dev.escalade.incident.AttemptStatus.PENDING")
    int cancelPendingForIncident(@Param("incidentId") UUID incidentId);
}
