package dev.escalade.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the two background jobs on fixed intervals. Disabled with
 * {@code escalade.worker.scheduler-enabled=false} (used by tests, which invoke the jobs directly for
 * deterministic timing). Run several app instances and they cooperate safely via the SKIP LOCKED
 * claim — no leader election needed.
 */
@Component
@ConditionalOnProperty(name = "escalade.worker.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorkerScheduler.class);

    private final EscalationEngine engine;
    private final DeadLetterSweeper sweeper;

    public NotificationWorkerScheduler(EscalationEngine engine, DeadLetterSweeper sweeper) {
        this.engine = engine;
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${escalade.worker.poll-interval-ms:1000}")
    public void poll() {
        engine.tick();
    }

    @Scheduled(fixedDelayString = "${escalade.worker.sweep-interval-ms:5000}")
    public void sweep() {
        try {
            sweeper.sweep();
        } catch (OptimisticLockingFailureException collision) {
            // An acknowledgement landed mid-sweep; nothing was sent, so just let the next sweep re-read.
            log.debug("dead-letter sweep collided with a concurrent transition; retrying next cycle");
        }
    }
}
