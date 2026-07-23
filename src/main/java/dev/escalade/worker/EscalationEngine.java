package dev.escalade.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Drains due notification attempts, one transaction per attempt.
 *
 * <p>Deliberately not transactional itself: each {@link EscalationStepProcessor#processNextDue()}
 * call owns its own transaction so a lost optimistic-lock race affects only that attempt.
 */
@Service
public class EscalationEngine {

    private static final Logger log = LoggerFactory.getLogger(EscalationEngine.class);

    private final EscalationStepProcessor processor;
    private final int batchSize;

    public EscalationEngine(EscalationStepProcessor processor,
            @Value("${escalade.worker.batch-size:50}") int batchSize) {
        this.processor = processor;
        this.batchSize = batchSize;
    }

    /** Processes up to {@code batch-size} due attempts. Returns how many were delivered. */
    public int tick() {
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            try {
                if (!processor.processNextDue()) {
                    break;
                }
                processed++;
            } catch (OptimisticLockingFailureException collision) {
                // An acknowledge/resolve committed while this step was in flight. The step's
                // transaction rolled back, so its attempt is PENDING again and the next tick will
                // re-read the incident, see it is no longer OPEN, and cancel the attempt instead of
                // paging again. Stop here rather than immediately re-claiming the same row.
                log.info("escalation step lost the race to a concurrent acknowledge; "
                        + "attempt returned to PENDING and will be re-evaluated next tick");
                break;
            }
        }
        if (processed > 0) {
            log.debug("worker tick processed {} attempt(s)", processed);
        }
        return processed;
    }
}
