package dev.escalade.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The subset of Prometheus Alertmanager's webhook payload (schema version 4) that Escalade uses.
 * Unknown fields (version, groupLabels, commonLabels, externalURL, …) are ignored so the contract
 * survives Alertmanager adding fields.
 *
 * @see <a href="https://prometheus.io/docs/alerting/latest/configuration/#webhook_config">Alertmanager webhook</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerPayload(List<Alert> alerts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String fingerprint) {

        public boolean isResolved() {
            return "resolved".equalsIgnoreCase(status);
        }
    }
}
