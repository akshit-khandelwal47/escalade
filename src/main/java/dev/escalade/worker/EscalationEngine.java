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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The worker's unit of work. One {@link #tick()} claims a batch of due notification attempts
 * (via {@code FOR UPDATE SKIP LOCKED}), delivers each, and schedules the next escalation step.
 * The whole tick runs in a single transaction so the claimed rows stay locked — and thus
 * invisible to other workers — until their outcome is committed.
 */
@Service
public class EscalationEngine {

    private static final Logger log = LoggerFactory.getLogger(EscalationEngine.class);

    private final NotificationAttemptRepository attempts;
    private final IncidentRepository incidents;
    private final StepRepository steps;
    private final NotificationDispatcher dispatcher;
    private final int batchSize;

    public EscalationEngine(NotificationAttemptRepository attempts, IncidentRepository incidents,
            StepRepository steps, NotificationDispatcher dispatcher,
            @Value("${escalade.worker.batch-size:50}") int batchSize) {
        this.attempts = attempts;
        this.incidents = incidents;
        this.steps = steps;
        this.dispatcher = dispatcher;
        this.batchSize = batchSize;
    }

    /** Claims and processes one batch of due attempts. Returns the number claimed. */
    @Transactional
    public int tick() {
        Instant now = Instant.now();
        List<NotificationAttempt> claimed = attempts.claimDueAttempts(now, batchSize);
        for (NotificationAttempt attempt : claimed) {
            process(attempt, now);
        }
        if (!claimed.isEmpty()) {
            log.debug("worker tick processed {} attempt(s)", claimed.size());
        }
        return claimed.size();
    }

    private void process(NotificationAttempt attempt, Instant now) {
        Incident incident = incidents.findById(attempt.getIncidentId()).orElse(null);
        if (incident == null || incident.getStatus() != IncidentStatus.OPEN) {
            // Incident was acknowledged/resolved (or removed) — halt this page.
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
            // Policy exhausted, still unacked. The terminal DEAD_LETTERED transition lands in the
            // dead-letter phase; for now escalation simply stops with no further pending attempt.
            log.info("incident {} exhausted all {} step(s) unacknowledged",
                    incident.getId(), sent.getStepOrder() + 1);
            return;
        }

        Instant dueAt = now.plusSeconds(next.getDelaySeconds());
        attempts.save(new NotificationAttempt(
                incident.getId(), next.getStepOrder(), next.getChannel(), next.getTarget(), dueAt));
        // Advancing current_step mutates the managed incident, bumping its @Version on flush —
        // the hook the ack-vs-escalation race handling builds on next.
        incident.setCurrentStep(next.getStepOrder());
    }
}
