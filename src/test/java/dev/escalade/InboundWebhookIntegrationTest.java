package dev.escalade;

import static org.assertj.core.api.Assertions.assertThat;

import dev.escalade.organization.Organization;
import dev.escalade.organization.OrganizationRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The inbound webhook receivers: the generic shape and the Prometheus Alertmanager shape, including
 * the auto-resolve when Alertmanager reports an alert cleared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "escalade.worker.scheduler-enabled=false")
@Testcontainers
class InboundWebhookIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestRestTemplate rest;
    @Autowired OrganizationRepository organizations;

    // A fresh org per test: the container is shared across methods, so a fixed key would let one
    // test's OPEN incidents pollute another's org-scoped "?status=OPEN" counts.
    private String apiKey;

    @BeforeEach
    void seedOrg() {
        apiKey = "wh_" + UUID.randomUUID();
        organizations.save(new Organization("Webhook Org", apiKey));
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(apiKey);
        return h;
    }

    private String createPolicy() {
        String body = """
            {"name":"On-call","steps":[{"channel":"SLACK","target":"#alerts","delaySeconds":0}]}""";
        ResponseEntity<Map> res = rest.exchange("/api/v1/policies", HttpMethod.POST,
                new HttpEntity<>(body, auth()), Map.class);
        return (String) res.getBody().get("id");
    }

    @Test
    void genericInbound_createsIncident_thenDeduplicatesOnRetry() {
        String policyId = createPolicy();
        String url = "/api/v1/webhooks/inbound?policyId=" + policyId;
        String body = """
            {"title":"Disk almost full","dedupKey":"disk-prod-01","payload":{"host":"prod-01"}}""";

        ResponseEntity<Map> first = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, auth()), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("deduplicated")).isEqualTo(false);
        String incidentId = (String) first.getBody().get("incidentId");

        // A monitor retrying on timeout must not open a second incident.
        ResponseEntity<Map> retry = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, auth()), Map.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("deduplicated")).isEqualTo(true);
        assertThat(retry.getBody().get("incidentId")).isEqualTo(incidentId);
    }

    @Test
    void inbound_missingPolicyId_is400() {
        ResponseEntity<Map> res = rest.exchange("/api/v1/webhooks/inbound", HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"x\"}", auth()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void alertmanager_firingCreates_thenResolvedClosesTheSameIncident() {
        String policyId = createPolicy();
        String url = "/api/v1/webhooks/alertmanager?policyId=" + policyId;

        String firing = """
            {"alerts":[{"status":"firing","fingerprint":"fp-123",
              "labels":{"alertname":"HighLatency","severity":"critical"},
              "annotations":{"summary":"p99 latency over 2s"}}]}""";
        ResponseEntity<Map> fired = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(firing, auth()), Map.class);
        assertThat(fired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fired.getBody().get("created")).isEqualTo(1);

        // Same fingerprint firing again deduplicates rather than opening a second incident.
        ResponseEntity<Map> again = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(firing, auth()), Map.class);
        assertThat(again.getBody().get("created")).isEqualTo(0);
        assertThat(again.getBody().get("deduplicated")).isEqualTo(1);

        // Confirm exactly one incident is OPEN for this fingerprint.
        ResponseEntity<List> openList = rest.exchange("/api/v1/incidents?status=OPEN", HttpMethod.GET,
                new HttpEntity<>(auth()), List.class);
        assertThat(openList.getBody()).hasSize(1);

        // Alertmanager now reports the alert cleared → the incident resolves without human action.
        String resolved = """
            {"alerts":[{"status":"resolved","fingerprint":"fp-123",
              "labels":{"alertname":"HighLatency"}}]}""";
        ResponseEntity<Map> closed = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(resolved, auth()), Map.class);
        assertThat(closed.getBody().get("resolved")).isEqualTo(1);

        ResponseEntity<List> stillOpen = rest.exchange("/api/v1/incidents?status=OPEN", HttpMethod.GET,
                new HttpEntity<>(auth()), List.class);
        assertThat(stillOpen.getBody()).isEmpty();
    }

    @Test
    void alertmanager_resolvedForUnknownAlert_isIgnored() {
        String policyId = createPolicy();
        String body = """
            {"alerts":[{"status":"resolved","fingerprint":"never-seen","labels":{"alertname":"Ghost"}}]}""";
        ResponseEntity<Map> res = rest.exchange("/api/v1/webhooks/alertmanager?policyId=" + policyId,
                HttpMethod.POST, new HttpEntity<>(body, auth()), Map.class);
        assertThat(res.getBody().get("resolved")).isEqualTo(0);
        assertThat(res.getBody().get("ignored")).isEqualTo(1);
    }

    @Test
    void alertmanager_emptyOrMissingAlerts_is200_notAServerError() {
        String policyId = createPolicy();
        ResponseEntity<Map> res = rest.exchange("/api/v1/webhooks/alertmanager?policyId=" + policyId,
                HttpMethod.POST, new HttpEntity<>("{}", auth()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("created")).isEqualTo(0);
    }
}
