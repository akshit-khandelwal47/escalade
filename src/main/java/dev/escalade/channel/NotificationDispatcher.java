package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.policy.Channel;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes an attempt to the first {@link NotificationChannel} that supports its channel type.
 * Channels are injected in {@code @Order} sequence, so the logging fallback (ordered last)
 * only wins when no real transport claims the type.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels;

    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    /**
     * Reports which channel type is handled by which transport at startup. A page that silently went
     * to a log file instead of Slack is the kind of surprise this system exists to prevent, so the
     * fallbacks are stated plainly rather than left to be discovered during an incident.
     */
    @PostConstruct
    void reportRouting() {
        String routing = Arrays.stream(Channel.values())
                .map(type -> type + "=" + channels.stream()
                        .filter(c -> c.supports(type))
                        .findFirst()
                        .map(c -> c.getClass().getSimpleName())
                        .orElse("NONE"))
                .collect(Collectors.joining(", "));
        log.info("notification routing: {}", routing);
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
