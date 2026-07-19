package dev.escalade.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the policy API. */
public final class PolicyDtos {

    private PolicyDtos() {}

    public record CreatePolicyRequest(
            @NotBlank String name,
            @NotEmpty @Valid List<StepRequest> steps) {}

    public record StepRequest(
            @NotNull Channel channel,
            @NotBlank String target,
            @Min(0) int delaySeconds) {}

    public record PolicyResponse(UUID id, String name, Instant createdAt, List<StepResponse> steps) {}

    public record StepResponse(int stepOrder, Channel channel, String target, int delaySeconds) {}
}
