package dev.escalade.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link EscalationEngine#tick()} on a fixed interval. Disabled with
 * {@code escalade.worker.scheduler-enabled=false} (used by tests, which invoke {@code tick()}
 * directly for deterministic timing). Run several app instances and they cooperate safely via
 * the SKIP LOCKED claim — no leader election needed.
 */
@Component
@ConditionalOnProperty(name = "escalade.worker.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationWorkerScheduler {

    private final EscalationEngine engine;

    public NotificationWorkerScheduler(EscalationEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${escalade.worker.poll-interval-ms:1000}")
    public void poll() {
        engine.tick();
    }
}
