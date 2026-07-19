package dev.escalade.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "escalation_policy")
@Getter
@Setter
@NoArgsConstructor
public class EscalationPolicy {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public EscalationPolicy(UUID orgId, String name) {
        this.orgId = orgId;
        this.name = name;
    }
}
