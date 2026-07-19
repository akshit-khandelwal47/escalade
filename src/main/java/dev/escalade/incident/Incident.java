package dev.escalade.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "incident")
@Getter
@Setter
@NoArgsConstructor
public class Incident {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(nullable = false)
    private String title;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Column(name = "current_step", nullable = false)
    private int currentStep = 0;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Incident(UUID orgId, UUID policyId, String title, String dedupKey, Map<String, Object> payload) {
        this.orgId = orgId;
        this.policyId = policyId;
        this.title = title;
        this.dedupKey = dedupKey;
        this.payload = payload;
    }
}
