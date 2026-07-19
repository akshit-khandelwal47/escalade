package dev.escalade.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<Incident> findByOrgIdAndDedupKeyAndStatus(UUID orgId, String dedupKey, IncidentStatus status);

    List<Incident> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<Incident> findByOrgIdAndStatusOrderByCreatedAtDesc(UUID orgId, IncidentStatus status);
}
