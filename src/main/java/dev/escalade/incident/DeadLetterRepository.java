package dev.escalade.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {

    /** Org-scoped so one tenant can never read another's delivery failures. */
    @Query("select d from DeadLetter d where d.incidentId in "
            + "(select i.id from Incident i where i.orgId = :orgId) order by d.failedAt desc")
    List<DeadLetter> findByOrgId(@Param("orgId") UUID orgId);

    List<DeadLetter> findByIncidentIdOrderByFailedAtDesc(UUID incidentId);
}
