package dev.escalade.worker;

import dev.escalade.channel.NotificationDispatcher;
import dev.escalade.incident.AttemptStatus;
import dev.escalade.incident.DeadLetter;
import dev.escalade.incident.DeadLetterRepository;
import dev.escalade.incident.Incident;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentStatus;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.incident.NotificationAttemptRepository;
import dev.escalade.metrics.EscaladeMetrics;
import dev.escalade.policy.EscalationStep;
import dev.escalade.policy.StepRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final DeadLetterRepository deadLetters;
    private final NotificationDispatcher dispatcher;
    private final EscaladeMetrics metrics;
    private final int maxAttempts;
    private final long backoffSeconds;
    private final long maxBackoffSeconds;

    public EscalationStepProcessor(NotificationAttemptRepository attempts, IncidentRepository incidents,
            StepRepository steps, DeadLetterRepository deadLetters, NotificationDispatcher dispatcher,
            EscaladeMetrics metrics,
            @Value("${escalade.worker.max-attempts:3}") int maxAttempts,
            @Value("${escalade.worker.retry-backoff-seconds:30}") long backoffSeconds,
            @Value("${escalade.worker.retry-max-backoff-seconds:900}") long maxBackoffSeconds) {
        this.attempts = attempts;
        this.incidents = incidents;
        this.steps = steps;
        this.deadLetters = deadLetters;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.backoffSeconds = backoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
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
            attempt.setLastError(null);
            metrics.pageSent(attempt.getChannel());
            scheduleNextStep(incident, attempt, now);
        } catch (Exception e) {
            handleDeliveryFailure(incident, attempt, now, e);
        }
    }

    /**
     * Retries with exponential backoff until {@code max-attempts}, then dead-letters.
     *
     * <p>Dead-lettering does <em>not</em> stop the escalation: the ladder continues to the next step.
     * A channel outage must not silently prevent anyone from being paged — that is the entire reason
     * a policy has more than one step. The failure becomes a queryable {@code dead_letter} row rather
     * than a swallowed exception.
     */
    private void handleDeliveryFailure(Incident incident, NotificationAttempt attempt, Instant now, Exception e) {
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        attempt.setLastError(error);
        metrics.pageFailed(attempt.getChannel());

        if (attempt.getAttemptCount() < maxAttempts) {
            attempt.setStatus(AttemptStatus.PENDING);
            attempt.setDueAt(now.plusSeconds(backoffFor(attempt.getAttemptCount())));
            log.warn("delivery failed for attempt {} (incident {}), retry {}/{} due at {}: {}",
                    attempt.getId(), incident.getId(), attempt.getAttemptCount(), maxAttempts,
                    attempt.getDueAt(), error);
            return;
        }

        attempt.setStatus(AttemptStatus.FAILED);
        metrics.attemptDeadLettered(attempt.getChannel());
        deadLetters.save(new DeadLetter(attempt.getId(), incident.getId(),
                "Delivery failed after " + attempt.getAttemptCount() + " attempt(s): " + error));
        log.error("dead-lettered attempt {} (incident {}) after {} attempt(s): {}",
                attempt.getId(), incident.getId(), attempt.getAttemptCount(), error);

        // Keep escalating regardless — the next channel may still reach someone.
        scheduleNextStep(incident, attempt, now);
    }

    /** Exponential backoff: base, 2x base, 4x base … capped. */
    private long backoffFor(int attemptCount) {
        long factor = 1L << Math.min(attemptCount - 1, 16);
        return Math.min(backoffSeconds * factor, maxBackoffSeconds);
    }

    private void scheduleNextStep(Incident incident, NotificationAttempt finished, Instant now) {
        int nextOrder = finished.getStepOrder() + 1;
        EscalationStep next = steps.findByPolicyIdOrderByStepOrder(incident.getPolicyId()).stream()
                .filter(s -> s.getStepOrder() == nextOrder)
                .findFirst()
                .orElse(null);

        if (next == null) {
            // Policy exhausted and still unacknowledged. Stamp the time; the sweeper flips the
            // incident to DEAD_LETTERED once the grace period passes, so a late ack still works.
            if (incident.getEscalationExhaustedAt() == null) {
                incident.setEscalationExhaustedAt(now);
                metrics.escalationExhausted();
            }
            log.info("incident {} exhausted all {} step(s) unacknowledged",
                    incident.getId(), finished.getStepOrder() + 1);
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
