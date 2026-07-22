package dev.escalade.incident;

import dev.escalade.policy.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response payloads for the incident API. */
public final class IncidentDtos {

    private IncidentDtos() {}

    public record CreateIncidentRequest(
            @NotNull UUID policyId,
            @NotBlank String title,
            @NotBlank String dedupKey,
            Map<String, Object> payload) {}

    public record IncidentResponse(
            UUID id,
            UUID policyId,
            String title,
            String dedupKey,
            IncidentStatus status,
            int currentStep,
            Instant createdAt,
            Instant ackedAt,
            Instant resolvedAt,
            List<AttemptResponse> timeline) {}

    public record AttemptResponse(
            int stepOrder,
            Channel channel,
            String target,
            AttemptStatus status,
            int attemptCount,
            Instant dueAt,
            Instant sentAt,
            String lastError) {}

    public record DeadLetterResponse(
            UUID id,
            UUID incidentId,
            UUID notificationAttemptId,
            String reason,
            Instant failedAt) {}
}
