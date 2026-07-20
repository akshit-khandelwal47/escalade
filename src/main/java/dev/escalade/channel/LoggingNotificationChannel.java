package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.policy.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fallback transport that logs the page instead of delivering it. Ordered last so that
 * real channels (added later) take precedence and this catches anything unconfigured.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationChannel.class);

    @Override
    public boolean supports(Channel channel) {
        return true;
    }

    @Override
    public void send(Incident incident, NotificationAttempt attempt) {
        log.info("PAGE [{} step {}] -> {}:{} | incident={} \"{}\"",
                attempt.getChannel(), attempt.getStepOrder(), attempt.getChannel(), attempt.getTarget(),
                incident.getId(), incident.getTitle());
    }
}
