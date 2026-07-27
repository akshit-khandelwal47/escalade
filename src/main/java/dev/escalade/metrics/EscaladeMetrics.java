package dev.escalade.metrics;

import dev.escalade.incident.AttemptStatus;
import dev.escalade.incident.DeadLetterRepository;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentStatus;
import dev.escalade.incident.NotificationAttemptRepository;
import dev.escalade.policy.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Operational counters and gauges for the escalation pipeline.
 *
 * <p>An alerting system that cannot answer "is it still delivering?" has the same problem it exists
 * to solve. The counters here are chosen so the failure modes are visible without reading logs:
 * deliveries that failed, attempts that dead-lettered, incidents nobody acknowledged, and
 * acknowledgements that collided with the worker.
 *
 * <p>The gauges query the database when scraped. That is fine at this scale and keeps the numbers
 * authoritative rather than drifting in-memory counters; a large deployment would move them to a
 * periodically refreshed snapshot.
 */
@Component
public class EscaladeMetrics {

    private final MeterRegistry registry;

    public EscaladeMetrics(MeterRegistry registry, IncidentRepository incidents,
            NotificationAttemptRepository attempts, DeadLetterRepository deadLetters) {
        this.registry = registry;

        Gauge.builder("escalade.incidents.open", incidents, r -> r.countByStatus(IncidentStatus.OPEN))
                .description("Incidents currently open and escalating")
                .register(registry);
        Gauge.builder("escalade.incidents.dead_lettered", incidents,
                        r -> r.countByStatus(IncidentStatus.DEAD_LETTERED))
                .description("Incidents whose escalation was exhausted without an acknowledgement")
                .register(registry);
        Gauge.builder("escalade.attempts.pending", attempts,
                        r -> r.countByStatus(AttemptStatus.PENDING))
                .description("Notification attempts waiting to be delivered")
                .register(registry);
        Gauge.builder("escalade.dead_letters", deadLetters, DeadLetterRepository::count)
                .description("Deliveries that exhausted their retries")
                .register(registry);
    }

    /**
     * Named "triggered" rather than "created": Prometheus reserves the {@code _created} suffix for
     * OpenMetrics timestamps, and a counter called {@code escalade.incidents.created} is rendered as
     * {@code escalade_incidents_total} — which reads like a count of all incidents.
     */
    public void incidentCreated() {
        registry.counter("escalade.incidents.triggered").increment();
    }

    /** A retried trigger that matched an existing open incident instead of paging again. */
    public void incidentDeduplicated() {
        registry.counter("escalade.incidents.deduplicated").increment();
    }

    public void pageSent(Channel channel) {
        registry.counter("escalade.pages.sent", "channel", channel.name()).increment();
    }

    public void pageFailed(Channel channel) {
        registry.counter("escalade.pages.failed", "channel", channel.name()).increment();
    }

    public void attemptDeadLettered(Channel channel) {
        registry.counter("escalade.attempts.dead_lettered", "channel", channel.name()).increment();
    }

    public void escalationExhausted() {
        registry.counter("escalade.escalations.exhausted").increment();
    }

    /** An acknowledgement that raced the worker and had to retry. */
    public void ackCollision() {
        registry.counter("escalade.acks.collisions").increment();
    }
}
