package dev.escalade.webhook;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Request/response payloads for the inbound webhook endpoints. */
public final class WebhookDtos {

    private WebhookDtos() {}

    /**
     * The generic inbound shape — what a demo script or a simple monitor posts.
     *
     * <p>{@code dedupKey} is optional: monitoring systems that retry on timeout should send a stable
     * one so a retry collapses onto the same incident, but when it is absent the title is a reasonable
     * stable fallback.
     */
    public record InboundAlert(@NotBlank String title, String dedupKey, Map<String, Object> payload) {

        public String effectiveDedupKey() {
            return (dedupKey != null && !dedupKey.isBlank()) ? dedupKey : title;
        }
    }

    /**
     * Outcome of ingesting a batch (Alertmanager posts many alerts at once).
     *
     * @param created      firing alerts that opened a new incident
     * @param deduplicated firing alerts that matched an already-open incident
     * @param resolved     resolved alerts that closed a matching open incident
     * @param ignored      resolved alerts with nothing open to close
     */
    public record IngestResult(int created, int deduplicated, int resolved, int ignored) {}
}
