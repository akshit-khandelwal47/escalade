package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.policy.Channel;

/**
 * A delivery transport for a notification attempt. Implementations are selected by
 * {@link #supports(Channel)}; real transports (Slack, email) arrive in a later phase,
 * with {@link LoggingNotificationChannel} as the catch-all fallback.
 */
public interface NotificationChannel {

    boolean supports(Channel channel);

    /**
     * Deliver the notification. Throwing signals a delivery failure — the engine records it
     * on the attempt (retry/backoff and dead-lettering land in a later phase).
     */
    void send(Incident incident, NotificationAttempt attempt) throws Exception;
}
