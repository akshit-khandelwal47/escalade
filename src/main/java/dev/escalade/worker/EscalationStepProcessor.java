package dev.escalade.worker;

import dev.escalade.channel.NotificationDispatcher;
import dev.escalade.incident.AttemptStatus;
import dev.escalade.incident.Incident;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentStatus;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.incident.NotificationAttemptRepository;
import dev.escalade.policy.EscalationStep;
import dev.escalade.policy.StepRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims and processes exactly one due attempt per transaction.
 *
 * <p>One attempt per transaction is deliberate. The transaction ends by writing to the incident
 * (advancing {@code current_step}), which bumps its {@code @Version}; if an acknowledgement commits
 * first, that write fails and the transaction rolls back. Batching many attempts into one
 * transaction would let a single such collision roll back attempts that were already delivered,
 * re-paging them on the next tick. Isolating each attempt keeps the blast radius to one page.
 */
@Component
public class EscalationStepProcessor {

    private static final Logger log = LoggerFactory.getLogger(EscalationStepProcessor.class);

    private final NotificationAttemptRepository attempts;
    private final IncidentRepository incidents;
    private final StepRepository steps;
    private final NotificationDispatcher dispatcher;

    public EscalationStepProcessor(NotificationAttemptRepository attempts, IncidentRepository incidents,
            StepRepository steps, NotificationDispatcher dispatcher) {
        this.attempts = attempts;
        this.incidents = incidents;
        this.steps = steps;
        this.dispatcher = dispatcher;
    }

    /**
     * Claims the single oldest due attempt and processes it.
     *
     * @return {@code false} when nothing was due, so the caller can stop polling this tick.
     */
    @Transactional
    public boolean processNextDue() {
        Instant now = Instant.now();
        List<NotificationAttempt> claimed = attempts.claimDueAttempts(now, 1);
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.get(0), now);
        return true;
    }

    private void process(NotificationAttempt attempt, Instant now) {
        Incident incident = incidents.findById(attempt.getIncidentId()).orElse(null);
        if (incident == null || incident.getStatus() != IncidentStatus.OPEN) {
            // Already acknowledged/resolved (or removed) — halt this page.
            attempt.setStatus(AttemptStatus.CANCELLED);
            return;
        }

        attempt.setAttemptCount(attempt.getAttemptCount() + 1);
        try {
            dispatcher.dispatch(incident, attempt);
            attempt.setStatus(AttemptStatus.SENT);
            attempt.setSentAt(now);
            scheduleNextStep(incident, attempt, now);
        } catch (Exception e) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setLastError(e.getMessage());
            log.warn("delivery failed for attempt {} (incident {}): {}",
                    attempt.getId(), incident.getId(), e.getMessage());
            // Retry with backoff + dead-lettering land in a later phase.
        }
    }

    private void scheduleNextStep(Incident incident, NotificationAttempt sent, Instant now) {
        int nextOrder = sent.getStepOrder() + 1;
        EscalationStep next = steps.findByPolicyIdOrderByStepOrder(incident.getPolicyId()).stream()
                .filter(s -> s.getStepOrder() == nextOrder)
                .findFirst()
                .orElse(null);

        if (next == null) {
            log.info("incident {} exhausted all {} step(s) unacknowledged",
                    incident.getId(), sent.getStepOrder() + 1);
            return;
        }

        Instant dueAt = now.plusSeconds(next.getDelaySeconds());
        attempts.save(new NotificationAttempt(
                incident.getId(), next.getStepOrder(), next.getChannel(), next.getTarget(), dueAt));
        // Advancing current_step mutates the managed incident, so its @Version is checked on flush.
        // A concurrent ack that committed since we loaded it makes this commit fail — by design.
        incident.setCurrentStep(next.getStepOrder());
    }
}
