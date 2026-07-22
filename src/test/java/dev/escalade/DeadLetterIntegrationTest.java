package dev.escalade;

import static org.assertj.core.api.Assertions.assertThat;

import dev.escalade.channel.NotificationChannel;
import dev.escalade.incident.AttemptStatus;
import dev.escalade.incident.DeadLetter;
import dev.escalade.incident.DeadLetterRepository;
import dev.escalade.incident.Incident;
import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentService;
import dev.escalade.incident.IncidentStatus;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.incident.NotificationAttemptRepository;
import dev.escalade.organization.Organization;
import dev.escalade.organization.OrganizationRepository;
import dev.escalade.policy.Channel;
import dev.escalade.policy.PolicyDtos.CreatePolicyRequest;
import dev.escalade.policy.PolicyDtos.StepRequest;
import dev.escalade.policy.PolicyService;
import dev.escalade.worker.DeadLetterSweeper;
import dev.escalade.worker.EscalationEngine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Retry/backoff, the dead-letter path, and the DEAD_LETTERED terminal state.
 *
 * <p>{@code batch-size=1} makes each {@code tick()} process exactly one attempt, so the retry
 * sequence can be asserted step by step. Backoff is zeroed so retries are immediately due.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "escalade.worker.scheduler-enabled=false",
    "escalade.worker.batch-size=1",
    "escalade.worker.max-attempts=2",
    "escalade.worker.retry-backoff-seconds=0",
    "escalade.worker.unacked-grace-seconds=0"
})
@Testcontainers
class DeadLetterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Channel whose delivery outcome the test controls. */
    @TestConfiguration
    static class FlakyChannelConfig {

        static final AtomicBoolean FAIL = new AtomicBoolean(true);

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        NotificationChannel flakyChannel() {
            return new NotificationChannel() {
                @Override
                public boolean supports(Channel channel) {
                    return true;
                }

                @Override
                public void send(Incident incident, NotificationAttempt attempt) throws Exception {
                    if (FAIL.get()) {
                        throw new IllegalStateException("channel unreachable");
                    }
                }
            };
        }
    }

    @Autowired EscalationEngine engine;
    @Autowired DeadLetterSweeper sweeper;
    @Autowired PolicyService policyService;
    @Autowired IncidentService incidentService;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationAttemptRepository attempts;
    @Autowired DeadLetterRepository deadLetters;
    @Autowired OrganizationRepository organizations;

    private UUID seedOrg() {
        return organizations.save(new Organization("DL Org", "dl_" + UUID.randomUUID())).getId();
    }

    private UUID createIncident(UUID orgId, UUID policyId, String dedupKey) {
        return incidentService.create(orgId,
                new CreateIncidentRequest(policyId, "Broken thing", dedupKey, null)).incident().id();
    }

    @Test
    void deliveryFailure_retriesThenDeadLetters_butEscalationStillContinues() {
        FlakyChannelConfig.FAIL.set(true);
        UUID orgId = seedOrg();
        UUID policyId = policyService.create(orgId, new CreatePolicyRequest("On-call", List.of(
                new StepRequest(Channel.SLACK, "#alerts", 0),
                new StepRequest(Channel.EMAIL, "oncall@example.com", 0)))).id();
        UUID incidentId = createIncident(orgId, policyId, "dl-continue");

        // First delivery fails: not yet at the cap, so it goes back to PENDING for retry.
        assertThat(engine.tick()).isEqualTo(1);
        NotificationAttempt step0 = attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId).get(0);
        assertThat(step0.getStatus()).isEqualTo(AttemptStatus.PENDING);
        assertThat(step0.getAttemptCount()).isEqualTo(1);
        assertThat(step0.getLastError()).contains("channel unreachable");
        assertThat(deadLetters.findByIncidentIdOrderByFailedAtDesc(incidentId)).isEmpty();

        // Second failure hits max-attempts: the attempt dead-letters.
        assertThat(engine.tick()).isEqualTo(1);
        List<NotificationAttempt> timeline =
                attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId);
        assertThat(timeline.get(0).getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(timeline.get(0).getAttemptCount()).isEqualTo(2);

        List<DeadLetter> dead = deadLetters.findByIncidentIdOrderByFailedAtDesc(incidentId);
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).getReason()).contains("channel unreachable");

        // The point: a dead channel must not stop the ladder. Step 1 was still scheduled.
        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(1).getStepOrder()).isEqualTo(1);
        assertThat(timeline.get(1).getStatus()).isEqualTo(AttemptStatus.PENDING);
        assertThat(incidents.findById(incidentId).orElseThrow().getCurrentStep()).isEqualTo(1);
    }

    @Test
    void exhaustedEscalation_isSweptToDeadLettered_andStillAcceptsALateAck() {
        FlakyChannelConfig.FAIL.set(false);
        UUID orgId = seedOrg();
        UUID policyId = policyService.create(orgId, new CreatePolicyRequest("Single step", List.of(
                new StepRequest(Channel.SLACK, "#alerts", 0)))).id();
        UUID incidentId = createIncident(orgId, policyId, "dl-exhaust");

        // Only step delivers successfully, and nothing remains to escalate to.
        assertThat(engine.tick()).isEqualTo(1);
        Incident afterSend = incidents.findById(incidentId).orElseThrow();
        assertThat(afterSend.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(afterSend.getEscalationExhaustedAt()).isNotNull();

        // With a zero grace period the sweeper marks it immediately.
        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(incidents.findById(incidentId).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.DEAD_LETTERED);

        // Giving up on paging must not lock out a human who shows up late.
        incidentService.acknowledge(orgId, incidentId);
        assertThat(incidents.findById(incidentId).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.ACKNOWLEDGED);
    }
}
