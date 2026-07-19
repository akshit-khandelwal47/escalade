package dev.escalade.policy;

import dev.escalade.auth.CurrentOrg;
import dev.escalade.organization.Organization;
import dev.escalade.policy.PolicyDtos.CreatePolicyRequest;
import dev.escalade.policy.PolicyDtos.PolicyResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse create(@CurrentOrg Organization org, @Valid @RequestBody CreatePolicyRequest req) {
        return policyService.create(org.getId(), req);
    }

    @GetMapping("/{id}")
    public PolicyResponse get(@CurrentOrg Organization org, @PathVariable UUID id) {
        return policyService.get(org.getId(), id);
    }
}
