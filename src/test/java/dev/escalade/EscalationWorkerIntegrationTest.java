package dev.escalade;

import static org.assertj.core.api.Assertions.assertThat;

import dev.escalade.incident.AttemptStatus;
import dev.escalade.incident.Incident;
import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the worker's claim-and-escalate loop against real Postgres. The background scheduler
 * is disabled so the test drives {@link EscalationEngine#tick()} deterministically.
 */
@SpringBootTest
@TestPropertySource(properties = "escalade.worker.scheduler-enabled=false")
@Testcontainers
class EscalationWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired EscalationEngine engine;
    @Autowired PolicyService policyService;
    @Autowired IncidentService incidentService;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationAttemptRepository attempts;
    @Autowired OrganizationRepository organizations;

    private UUID seedOrg() {
        return organizations.save(new Organization("Worker Org", "wk_" + UUID.randomUUID())).getId();
    }

    private UUID twoStepPolicy(UUID orgId) {
        var req = new CreatePolicyRequest("On-call", List.of(
                new StepRequest(Channel.SLACK, "#alerts", 0),
                new StepRequest(Channel.EMAIL, "oncall@example.com", 30)));
        return policyService.create(orgId, req).id();
    }

    @Test
    void tick_sendsDueStep_thenSchedulesNextStep() {
        UUID orgId = seedOrg();
        UUID policyId = twoStepPolicy(orgId);
        UUID incidentId = incidentService.create(orgId,
                new CreateIncidentRequest(policyId, "DB down", "db-prod", null)).incident().id();

        // Only step 0 exists and is due now.
        assertThat(attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId)).hasSize(1);

        int processed = engine.tick();
        assertThat(processed).isEqualTo(1);

        List<NotificationAttempt> timeline = attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId);
        assertThat(timeline).hasSize(2);
        NotificationAttempt step0 = timeline.get(0);
        NotificationAttempt step1 = timeline.get(1);
        assertThat(step0.getStatus()).isEqualTo(AttemptStatus.SENT);
        assertThat(step0.getSentAt()).isNotNull();
        assertThat(step1.getStepOrder()).isEqualTo(1);
        assertThat(step1.getStatus()).isEqualTo(AttemptStatus.PENDING);

        Incident reloaded = incidents.findById(incidentId).orElseThrow();
        assertThat(reloaded.getCurrentStep()).isEqualTo(1);

        // Step 1 is due in 30s, so an immediate tick claims nothing and creates no step 2.
        assertThat(engine.tick()).isZero();
        assertThat(attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId)).hasSize(2);
    }

    @Test
    void tick_doesNotSend_whenIncidentAlreadyAcknowledged() {
        UUID orgId = seedOrg();
        UUID policyId = twoStepPolicy(orgId);
        UUID incidentId = incidentService.create(orgId,
                new CreateIncidentRequest(policyId, "Flapping", "flap", null)).incident().id();

        incidentService.acknowledge(orgId, incidentId);

        // Ack cancelled the pending attempt, so the worker claims nothing and never pages.
        assertThat(engine.tick()).isZero();
        List<NotificationAttempt> timeline = attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(incidentId);
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getStatus()).isEqualTo(AttemptStatus.CANCELLED);
    }
}
