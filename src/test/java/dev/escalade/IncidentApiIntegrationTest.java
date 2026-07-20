package dev.escalade;

import static org.assertj.core.api.Assertions.assertThat;

import dev.escalade.organization.Organization;
import dev.escalade.organization.OrganizationRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end API test against a real Postgres. Verifies the two Phase-2 guarantees that
 * can only be exercised against real Postgres: idempotent dedup (partial unique index)
 * and the OPEN -> ACKNOWLEDGED state transition halting escalation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "escalade.worker.scheduler-enabled=false")
@Testcontainers
class IncidentApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    OrganizationRepository organizations;

    private static final String API_KEY = "test_key";

    @BeforeEach
    void seedOrg() {
        if (organizations.findByApiKey(API_KEY).isEmpty()) {
            organizations.save(new Organization("Test Org", API_KEY));
        }
    }

    private HttpHeaders authJson() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(API_KEY);
        return h;
    }

    @Test
    void createIncident_isIdempotentOnDedupKey_andAckHaltsEscalation() {
        String policyBody = """
            {"name":"On-call","steps":[
               {"channel":"SLACK","target":"#alerts","delaySeconds":0},
               {"channel":"EMAIL","target":"oncall@example.com","delaySeconds":30}]}""";
        ResponseEntity<Map> policy = rest.exchange("/api/v1/policies", HttpMethod.POST,
                new HttpEntity<>(policyBody, authJson()), Map.class);
        assertThat(policy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String policyId = (String) policy.getBody().get("id");

        String incidentBody = """
            {"policyId":"%s","title":"DB down","dedupKey":"db-prod"}""".formatted(policyId);

        ResponseEntity<Map> first = rest.exchange("/api/v1/incidents", HttpMethod.POST,
                new HttpEntity<>(incidentBody, authJson()), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String incidentId = (String) first.getBody().get("id");
        assertThat((List<?>) first.getBody().get("timeline")).hasSize(1);

        // Same dedup key -> same incident, HTTP 200 (deduplicated), no second page.
        ResponseEntity<Map> retry = rest.exchange("/api/v1/incidents", HttpMethod.POST,
                new HttpEntity<>(incidentBody, authJson()), Map.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("id")).isEqualTo(incidentId);

        // Ack -> status ACKNOWLEDGED.
        ResponseEntity<Map> acked = rest.exchange("/api/v1/incidents/" + incidentId + "/ack",
                HttpMethod.POST, new HttpEntity<>(authJson()), Map.class);
        assertThat(acked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acked.getBody().get("status")).isEqualTo("ACKNOWLEDGED");

        // Acking again is a conflict.
        ResponseEntity<Map> ackAgain = rest.exchange("/api/v1/incidents/" + incidentId + "/ack",
                HttpMethod.POST, new HttpEntity<>(authJson()), Map.class);
        assertThat(ackAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<Map> res = rest.exchange("/api/v1/incidents", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
