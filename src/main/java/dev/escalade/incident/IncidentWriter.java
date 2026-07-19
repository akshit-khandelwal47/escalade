package dev.escalade.incident;

import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.policy.EscalationStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single transaction that creates an incident together with its first
 * notification attempt. Kept separate from {@link IncidentService} so that a
 * unique-constraint violation on the dedup index rolls back cleanly and the
 * orchestrator can fall back to reading the existing incident in a fresh
 * transaction (a poisoned transaction cannot be read from).
 */
@Component
public class IncidentWriter {

    private final IncidentRepository incidents;
    private final NotificationAttemptRepository attempts;

    public IncidentWriter(IncidentRepository incidents, NotificationAttemptRepository attempts) {
        this.incidents = incidents;
        this.attempts = attempts;
    }

    @Transactional
    public Incident insertIncidentWithFirstAttempt(UUID orgId, CreateIncidentRequest req, List<EscalationStep> steps) {
        Incident incident = incidents.save(
                new Incident(orgId, req.policyId(), req.title(), req.dedupKey(), req.payload()));

        if (!steps.isEmpty()) {
            EscalationStep first = steps.get(0);
            Instant dueAt = incident.getCreatedAt().plusSeconds(first.getDelaySeconds());
            attempts.save(new NotificationAttempt(
                    incident.getId(), first.getStepOrder(), first.getChannel(), first.getTarget(), dueAt));
        }
        return incident;
    }
}
