package dev.escalade.incident;

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
@Table(name = "dead_letter")
@Getter
@Setter
@NoArgsConstructor
public class DeadLetter {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "notification_attempt_id", nullable = false)
    private UUID notificationAttemptId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt = Instant.now();

    public DeadLetter(UUID notificationAttemptId, UUID incidentId, String reason) {
        this.notificationAttemptId = notificationAttemptId;
        this.incidentId = incidentId;
        this.reason = reason;
    }
}
