package dev.escalade.policy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<EscalationPolicy, UUID> {
    Optional<EscalationPolicy> findByIdAndOrgId(UUID id, UUID orgId);
}
