package dev.escalade.webhook;

import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.incident.IncidentRepository;
import dev.escalade.incident.IncidentService;
import dev.escalade.incident.IncidentStatus;
import dev.escalade.metrics.EscaladeMetrics;
import dev.escalade.webhook.AlertmanagerPayload.Alert;
import dev.escalade.webhook.WebhookDtos.IngestResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Maps inbound monitoring payloads onto the incident model. All routing is by {@code policyId} from
 * the request URL — the webhook URL a user configures in their monitor is what selects the policy,
 * the same way every real webhook integration works.
 */
@Service
public class WebhookService {

    private final IncidentService incidents;
    private final IncidentRepository incidentRepository;
    private final EscaladeMetrics metrics;

    public WebhookService(IncidentService incidents, IncidentRepository incidentRepository,
            EscaladeMetrics metrics) {
        this.incidents = incidents;
        this.incidentRepository = incidentRepository;
        this.metrics = metrics;
    }

    /**
     * Ingests an Alertmanager batch. A firing alert triggers an incident keyed on its fingerprint;
     * the same fingerprint on a later {@code firing} deduplicates onto that incident, and a
     * {@code resolved} status resolves it. Escalade thus follows the monitor's own view of the world:
     * when Prometheus says the alert cleared, the incident closes without anyone touching it.
     */
    public IngestResult ingestAlertmanager(UUID orgId, UUID policyId, AlertmanagerPayload payload) {
        metrics.webhookReceived("alertmanager");
        if (payload == null || payload.alerts() == null || payload.alerts().isEmpty()) {
            return new IngestResult(0, 0, 0, 0);
        }

        int created = 0;
        int deduplicated = 0;
        int resolved = 0;
        int ignored = 0;

        for (Alert alert : payload.alerts()) {
            String dedupKey = dedupKeyFor(alert);
            if (alert.isResolved()) {
                var open = incidentRepository.findByOrgIdAndDedupKeyAndStatus(orgId, dedupKey, IncidentStatus.OPEN);
                if (open.isPresent()) {
                    incidents.resolve(orgId, open.get().getId());
                    resolved++;
                } else {
                    ignored++; // resolved for something never open here — nothing to do
                }
            } else {
                var result = incidents.create(orgId, new CreateIncidentRequest(
                        policyId, titleFor(alert), dedupKey, payloadFor(alert)));
                if (result.created()) {
                    created++;
                } else {
                    deduplicated++; // same fingerprint already firing — no second incident
                }
            }
        }
        return new IngestResult(created, deduplicated, resolved, ignored);
    }

    private String titleFor(Alert alert) {
        String summary = value(alert.annotations(), "summary");
        if (summary != null) {
            return summary;
        }
        String name = value(alert.labels(), "alertname");
        return name != null ? name : "Alert";
    }

    /**
     * Alertmanager sends a stable {@code fingerprint} per alert series — the natural dedup key. If a
     * sender omits it, fall back to the sorted label set, which is what the fingerprint is derived
     * from, so the key stays stable across retries either way.
     */
    private String dedupKeyFor(Alert alert) {
        if (alert.fingerprint() != null && !alert.fingerprint().isBlank()) {
            return alert.fingerprint();
        }
        Map<String, String> labels = alert.labels() == null ? Map.of() : new TreeMap<>(alert.labels());
        StringBuilder key = new StringBuilder();
        labels.forEach((k, v) -> key.append(k).append('=').append(v).append(','));
        return key.isEmpty() ? "unlabelled" : key.toString();
    }

    private Map<String, Object> payloadFor(Alert alert) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (alert.labels() != null) {
            payload.put("labels", alert.labels());
        }
        if (alert.annotations() != null) {
            payload.put("annotations", alert.annotations());
        }
        return payload.isEmpty() ? null : payload;
    }

    private static String value(Map<String, String> map, String key) {
        if (map == null) {
            return null;
        }
        String v = map.get(key);
        return (v != null && !v.isBlank()) ? v : null;
    }
}
