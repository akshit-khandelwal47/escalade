package dev.escalade.webhook;

import dev.escalade.auth.CurrentOrg;
import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.incident.IncidentService;
import dev.escalade.incident.IncidentService.CreateResult;
import dev.escalade.metrics.EscaladeMetrics;
import dev.escalade.organization.Organization;
import dev.escalade.webhook.WebhookDtos.InboundAlert;
import dev.escalade.webhook.WebhookDtos.IngestResult;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a monitoring system fire incidents in. Routing is by {@code policyId} in the query string:
 * the configured webhook URL is what picks the escalation policy. Same API-key auth as the rest of
 * {@code /api/**} — the URL is not itself a secret.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class InboundWebhookController {

    private final IncidentService incidents;
    private final WebhookService webhooks;
    private final EscaladeMetrics metrics;

    public InboundWebhookController(IncidentService incidents, WebhookService webhooks, EscaladeMetrics metrics) {
        this.incidents = incidents;
        this.webhooks = webhooks;
        this.metrics = metrics;
    }

    /**
     * Generic receiver. 201 for a new incident, 200 when the {@code dedupKey} matched an existing open
     * one — so a monitor retrying on timeout gets a success either way and never double-pages.
     */
    @PostMapping("/inbound")
    public ResponseEntity<Map<String, Object>> inbound(
            @CurrentOrg Organization org,
            @RequestParam UUID policyId,
            @Valid @RequestBody InboundAlert alert) {
        metrics.webhookReceived("generic");
        CreateResult result = incidents.create(org.getId(), new CreateIncidentRequest(
                policyId, alert.title(), alert.effectiveDedupKey(), alert.payload()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of(
                "incidentId", result.incident().id(),
                "deduplicated", !result.created()));
    }

    /** Prometheus Alertmanager receiver: firing alerts trigger incidents, resolved alerts close them. */
    @PostMapping("/alertmanager")
    public IngestResult alertmanager(
            @CurrentOrg Organization org,
            @RequestParam UUID policyId,
            @RequestBody AlertmanagerPayload payload) {
        return webhooks.ingestAlertmanager(org.getId(), policyId, payload);
    }
}
