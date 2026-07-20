package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Routes an attempt to the first {@link NotificationChannel} that supports its channel type.
 * Channels are injected in {@code @Order} sequence, so the logging fallback (ordered last)
 * only wins when no real transport claims the type.
 */
@Component
public class NotificationDispatcher {

    private final List<NotificationChannel> channels;

    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    public void dispatch(Incident incident, NotificationAttempt attempt) throws Exception {
        for (NotificationChannel channel : channels) {
            if (channel.supports(attempt.getChannel())) {
                channel.send(incident, attempt);
                return;
            }
        }
        throw new IllegalStateException("No channel supports " + attempt.getChannel());
    }
}
