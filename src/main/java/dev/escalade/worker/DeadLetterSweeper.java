package dev.escalade.worker;

import dev.escalade.incident.Incident;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flips incidents whose escalation ran out of steps to {@code DEAD_LETTERED}, once they have gone
 * unacknowledged for the grace period. This is what makes "nobody responded" an explicit, queryable
 * state rather than an incident sitting OPEN forever with nothing left to fire.
 *
 * <p>Unlike {@link EscalationStepProcessor}, a whole sweep shares one transaction. That is safe here
 * precisely because this transaction has no external side effects — nothing has been sent. If it
 * rolls back (say an acknowledgement collides), the next sweep simply re-reads and, finding the
 * incident no longer OPEN, skips it. The per-attempt isolation in the processor exists only because
 * a rollback there would re-page a human.
 */
@Component
public class DeadLetterSweeper {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterSweeper.class);

    private final IncidentRepository incidents;
    private final long graceSeconds;
    private final int batchSize;

    public DeadLetterSweeper(IncidentRepository incidents,
            @Value("${escalade.worker.unacked-grace-seconds:300}") long graceSeconds,
            @Value("${escalade.worker.batch-size:50}") int batchSize) {
        this.incidents = incidents;
        this.graceSeconds = graceSeconds;
        this.batchSize = batchSize;
    }

    /** @return how many incidents were dead-lettered. */
    @Transactional
    public int sweep() {
        Instant cutoff = Instant.now().minusSeconds(graceSeconds);
        List<Incident> exhausted = incidents.findExhaustedUnacked(cutoff, Limit.of(batchSize));
        for (Incident incident : exhausted) {
            incident.setStatus(IncidentStatus.DEAD_LETTERED);
            log.warn("incident {} dead-lettered: escalation exhausted and unacknowledged for {}s",
                    incident.getId(), graceSeconds);
        }
        return exhausted.size();
    }
}
