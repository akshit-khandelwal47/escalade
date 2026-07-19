package dev.escalade.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "escalation_step")
@Getter
@Setter
@NoArgsConstructor
public class EscalationStep {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(nullable = false)
    private String target;

    @Column(name = "delay_seconds", nullable = false)
    private int delaySeconds;

    public EscalationStep(UUID policyId, int stepOrder, Channel channel, String target, int delaySeconds) {
        this.policyId = policyId;
        this.stepOrder = stepOrder;
        this.channel = channel;
        this.target = target;
        this.delaySeconds = delaySeconds;
    }
}
