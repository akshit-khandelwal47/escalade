package dev.escalade.incident;

import dev.escalade.common.ConflictException;
import dev.escalade.common.NotFoundException;
import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.policy.EscalationStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the transactions that mutate an incident.
 *
 * <p>Kept separate from {@link IncidentService} for two reasons. Creation: a unique-constraint
 * violation on the dedup index must roll back cleanly so the orchestrator can read the existing
 * incident in a fresh transaction. Transitions: each attempt runs in its own transaction so the
 * orchestrator can retry after an optimistic-lock collision with the escalation worker — a retry
 * is only meaningful once the failed transaction has been rolled back.
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

    /**
     * Acknowledge and halt escalation. Throws {@code OptimisticLockingFailureException} on commit if
     * the worker advanced this incident's step concurrently; the caller retries.
     */
    /**
     * Acknowledge and halt escalation. Also accepted on a DEAD_LETTERED incident: escalation having
     * given up does not mean a human arriving late should be turned away.
     */
    @Transactional
    public Incident acknowledge(UUID orgId, UUID id) {
        Incident incident = load(orgId, id);
        if (incident.getStatus() != IncidentStatus.OPEN && incident.getStatus() != IncidentStatus.DEAD_LETTERED) {
            throw new ConflictException("Cannot acknowledge an incident in status " + incident.getStatus());
        }
        incident.setStatus(IncidentStatus.ACKNOWLEDGED);
        incident.setAckedAt(Instant.now());
        attempts.cancelPendingForIncident(id);
        return incident;
    }

    @Transactional
    public Incident resolve(UUID orgId, UUID id) {
        Incident incident = load(orgId, id);
        if (incident.getStatus().isTerminal()) {
            throw new ConflictException("Cannot resolve an incident in status " + incident.getStatus());
        }
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setResolvedAt(Instant.now());
        attempts.cancelPendingForIncident(id);
        return incident;
    }

    private Incident load(UUID orgId, UUID id) {
        return incidents.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Incident not found: " + id));
    }
}
