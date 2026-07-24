package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;

/**
 * Wording shared by every transport, so a page reads the same however it arrives.
 *
 * <p>Carries the incident id deliberately: it is what the recipient needs to acknowledge, and an
 * escalation that pages someone without telling them how to stop it is only half a system.
 */
final class PageMessage {

    private PageMessage() {}

    static String subject(Incident incident) {
        return "[Escalade] " + incident.getTitle();
    }

    static String body(Incident incident, NotificationAttempt attempt) {
        return """
                Incident: %s
                Status: %s
                Escalation step: %d
                Opened: %s

                Acknowledge to stop the escalation:
                  POST /api/v1/incidents/%s/ack"""
                .formatted(
                        incident.getTitle(),
                        incident.getStatus(),
                        attempt.getStepOrder(),
                        incident.getCreatedAt(),
                        incident.getId());
    }
}
