package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.policy.Channel;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers a page to a Slack incoming webhook.
 *
 * <p>Only registered when {@code escalade.channels.slack.webhook-url} is set. Without it the bean is
 * absent and SLACK steps fall through to {@link LoggingNotificationChannel}, so an unconfigured
 * deployment still runs — it just logs instead of posting.
 *
 * <p>A step's {@code target} may carry its own {@code hooks.slack.com} URL to route different
 * escalation steps to different channels; otherwise the configured default is used and the target is
 * included in the text as a label.
 */
@Component
@Order(0) // must outrank the logging fallback, whose LOWEST_PRECEDENCE equals the default
@OnNonEmptyProperty("escalade.channels.slack.webhook-url")
public class SlackNotificationChannel implements NotificationChannel {

    private static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/";

    private final RestClient rest;
    private final String defaultWebhookUrl;

    public SlackNotificationChannel(
            @Qualifier("channelRestClientBuilder") RestClient.Builder builder,
            @Value("${escalade.channels.slack.webhook-url}") String defaultWebhookUrl) {
        this.rest = builder.build();
        this.defaultWebhookUrl = defaultWebhookUrl;
    }

    @Override
    public boolean supports(Channel channel) {
        return channel == Channel.SLACK;
    }

    @Override
    public void send(Incident incident, NotificationAttempt attempt) {
        String target = attempt.getTarget();
        boolean targetIsWebhook = target.startsWith(SLACK_WEBHOOK_PREFIX);
        String url = targetIsWebhook ? target : defaultWebhookUrl;

        String text = targetIsWebhook
                ? "*%s*\n```%s```".formatted(PageMessage.subject(incident), PageMessage.body(incident, attempt))
                : "*%s* (%s)\n```%s```".formatted(
                        PageMessage.subject(incident), target, PageMessage.body(incident, attempt));

        // Non-2xx throws, which the worker records as a delivery failure and retries.
        rest.post()
                .uri(url)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
    }
}
