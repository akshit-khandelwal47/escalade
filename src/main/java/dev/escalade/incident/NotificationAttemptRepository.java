package dev.escalade.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByIncidentIdOrderByStepOrderAscCreatedAtAsc(UUID incidentId);

    /** Halt escalation: cancel any not-yet-sent attempts for an acked/resolved incident. */
    @Modifying
    @Query("update NotificationAttempt a set a.status = dev.escalade.incident.AttemptStatus.CANCELLED "
            + "where a.incidentId = :incidentId and a.status = dev.escalade.incident.AttemptStatus.PENDING")
    int cancelPendingForIncident(@Param("incidentId") UUID incidentId);
}
