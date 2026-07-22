package dev.escalade;

import static org.assertj.core.api.Assertions.assertThat;

import dev.escalade.channel.NotificationChannel;
import dev.escalade.incident.AttemptStatus;
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
import dev.escalade.worker.EscalationEngine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The acknowledge-vs-escalation race.
 *
 * <p>An on-call engineer hits ack at the exact moment the worker is firing the next escalation step.
 * Both transactions touch the same incident, so the loser is caught by the optimistic-lock version.
 * What must hold afterwards: the incident ends ACKNOWLEDGED, and the step the worker scheduled is
 * cancelled rather than paging someone after the incident was already handled.
 */
@SpringBootTest
@TestPropertySource(properties = "escalade.worker.scheduler-enabled=false")
@Testcontainers
class AckEscalationRaceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Holds the worker inside delivery so the ack can be issued while the step is in flight. */
    @TestConfiguration
    static class BlockingChannelConfig {

        static final CountDownLatch IN_SEND = new CountDownLatch(1);
        static final CountDownLatch RELEASE_SEND = new CountDownLatch(1);

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        NotificationChannel blockingChannel() {
            return new NotificationChannel() {
                @Override
                public boolean supports(Channel channel) {
                    return true;
                }

                @Override
                public void send(Incident incident, NotificationAttempt attempt) throws Exception {
                    IN_SEND.countDown();
                    RELEASE_SEND.await(15, TimeUnit.SECONDS);
                }
            };
        }
    }

    @Autowired EscalationEngine engine;
    @Autowired PolicyService policyService;
    @Autowired IncidentService incidentService;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationAttemptRepository attempts;
    @Autowired OrganizationRepository organizations;

    @Test
    void ackDuringInFlightEscalation_endsAcknowledgedAndCancelsTheScheduledStep() throws Exception {
        UUID orgId = organizations.save(new Organization("Race Org", "race_" + UUID.randomUUID())).getId();
        UUID policyId = policyService.create(orgId, new CreatePolicyRequest("On-call", List.of(
                new StepRequest(Channel.SLACK, "#alerts", 0),
                new StepRequest(Channel.EMAIL, "oncall@example.com", 60)))).id();
        UUID incidentId = incidentService.create(orgId,
                new CreateIncidentRequest(policyId, "Region down", "region-down", null)).incident().id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Worker claims step 0 and stalls mid-delivery, holding the attempt row lock.
            Future<Integer> worker = pool.submit(() -> engine.tick());
            assertThat(BlockingChannelConfig.IN_SEND.await(15, TimeUnit.SECONDS)).isTrue();

            // Ack arrives now: it reads the incident at the pre-escalation version, then blocks
            // behind the worker's row lock.
            Future<?> acker = pool.submit(() -> incidentService.acknowledge(orgId, incidentId));
            Thread.sleep(500);

            // Let the worker finish; its commit bumps the version out from under the ack, which
            // retries and wins on the second pass.
            BlockingChannelConfig.RELEASE_SEND.countDown();
            worker.get(20, TimeUnit.SECONDS);
            acker.get(20, TimeUnit.SECONDS);
        } finally {
            BlockingChannelConfig.RELEASE_SEND.countDown();
            pool.shutdownNow();
        }

        Incident finalState = incidents.findById(incidentId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);

        List<NotificationAttempt> timeline =
                attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId);
        assertThat(timeline).hasSize(2);
        // Step 0 was already in flight when the ack landed — at-least-once means it still went out.
        assertThat(timeline.get(0).getStatus()).isEqualTo(AttemptStatus.SENT);
        // Step 1 is the one that matters: it must never page after an acknowledgement.
        assertThat(timeline.get(1).getStepOrder()).isEqualTo(1);
        assertThat(timeline.get(1).getStatus()).isEqualTo(AttemptStatus.CANCELLED);
    }
}
