package dev.escalade.incident;

import dev.escalade.policy.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_attempt")
@Getter
@Setter
@NoArgsConstructor
public class NotificationAttempt {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(nullable = false)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptStatus status = AttemptStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public NotificationAttempt(UUID incidentId, int stepOrder, Channel channel, String target, Instant dueAt) {
        this.incidentId = incidentId;
        this.stepOrder = stepOrder;
        this.channel = channel;
        this.target = target;
        this.dueAt = dueAt;
    }
}
