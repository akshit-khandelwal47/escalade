package dev.escalade.policy;

import dev.escalade.common.NotFoundException;
import dev.escalade.policy.PolicyDtos.CreatePolicyRequest;
import dev.escalade.policy.PolicyDtos.PolicyResponse;
import dev.escalade.policy.PolicyDtos.StepRequest;
import dev.escalade.policy.PolicyDtos.StepResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyService {

    private final PolicyRepository policies;
    private final StepRepository steps;

    public PolicyService(PolicyRepository policies, StepRepository steps) {
        this.policies = policies;
        this.steps = steps;
    }

    @Transactional
    public PolicyResponse create(UUID orgId, CreatePolicyRequest req) {
        EscalationPolicy policy = policies.save(new EscalationPolicy(orgId, req.name()));
        int order = 0;
        for (StepRequest s : req.steps()) {
            steps.save(new EscalationStep(policy.getId(), order++, s.channel(), s.target(), s.delaySeconds()));
        }
        return toResponse(policy);
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(UUID orgId, UUID policyId) {
        EscalationPolicy policy = policies.findByIdAndOrgId(policyId, orgId)
                .orElseThrow(() -> new NotFoundException("Policy not found: " + policyId));
        return toResponse(policy);
    }

    private PolicyResponse toResponse(EscalationPolicy policy) {
        List<StepResponse> stepResponses = steps.findByPolicyIdOrderByStepOrder(policy.getId()).stream()
                .map(s -> new StepResponse(s.getStepOrder(), s.getChannel(), s.getTarget(), s.getDelaySeconds()))
                .toList();
        return new PolicyResponse(policy.getId(), policy.getName(), policy.getCreatedAt(), stepResponses);
    }
}
