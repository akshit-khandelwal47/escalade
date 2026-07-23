package dev.escalade.channel;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.policy.Channel;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers a page by email through Resend.
 *
 * <p>Only registered when {@code escalade.channels.email.api-key} is set; otherwise EMAIL steps fall
 * through to {@link LoggingNotificationChannel}. The key is read from the environment and never
 * logged — it is a credential, and delivery failures are reported by status code, not by echoing the
 * request.
 *
 * <p>The sending domain must be verified with the provider, and the recipient should be a real
 * address: bounces to invented addresses damage the sending domain's reputation for everyone using it.
 */
@Component
@Order(0) // must outrank the logging fallback, whose LOWEST_PRECEDENCE equals the default
@OnNonEmptyProperty("escalade.channels.email.api-key")
public class EmailNotificationChannel implements NotificationChannel {

    private final RestClient rest;
    private final String apiKey;
    private final String from;
    private final String baseUrl;

    public EmailNotificationChannel(
            @Qualifier("channelRestClientBuilder") RestClient.Builder builder,
            @Value("${escalade.channels.email.api-key}") String apiKey,
            @Value("${escalade.channels.email.from:Escalade <onboarding@resend.dev>}") String from,
            @Value("${escalade.channels.email.base-url:https://api.resend.com}") String baseUrl) {
        this.rest = builder.build();
        this.apiKey = apiKey;
        this.from = from;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean supports(Channel channel) {
        return channel == Channel.EMAIL;
    }

    @Override
    public void send(Incident incident, NotificationAttempt attempt) {
        Map<String, Object> payload = Map.of(
                "from", from,
                "to", List.of(attempt.getTarget()),
                "subject", PageMessage.subject(incident),
                "text", PageMessage.body(incident, attempt));

        // Non-2xx throws, which the worker records as a delivery failure and retries.
        rest.post()
                .uri(baseUrl + "/emails")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
