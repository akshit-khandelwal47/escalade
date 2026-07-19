package dev.escalade.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepRepository extends JpaRepository<EscalationStep, UUID> {
    List<EscalationStep> findByPolicyIdOrderByStepOrder(UUID policyId);
}
