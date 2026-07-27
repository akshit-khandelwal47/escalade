package dev.escalade.incident;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * Incidents whose escalation ran out of steps and stayed unacknowledged past the grace cutoff.
     * These are the ones the sweeper flips to DEAD_LETTERED.
     */
    @Query("select i from Incident i where i.status = dev.escalade.incident.IncidentStatus.OPEN "
            + "and i.escalationExhaustedAt is not null and i.escalationExhaustedAt <= :cutoff "
            + "order by i.escalationExhaustedAt")
    List<Incident> findExhaustedUnacked(@Param("cutoff") Instant cutoff, Limit limit);

    long countByStatus(IncidentStatus status);

    Optional<Incident> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<Incident> findByOrgIdAndDedupKeyAndStatus(UUID orgId, String dedupKey, IncidentStatus status);

    List<Incident> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<Incident> findByOrgIdAndStatusOrderByCreatedAtDesc(UUID orgId, IncidentStatus status);
}
