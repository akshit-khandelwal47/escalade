package dev.escalade.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.escalade.incident.Incident;
import dev.escalade.incident.NotificationAttempt;
import dev.escalade.policy.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Transport-level tests: what actually goes over the wire, and that a failed delivery surfaces as an
 * exception so the worker can retry and eventually dead-letter it. No Postgres or Docker needed.
 */
class NotificationChannelTest {

    private static final String SLACK_URL = "https://hooks.slack.com/services/T000/B000/xxx";

    private Incident incident() {
        Incident incident = new Incident(UUID.randomUUID(), UUID.randomUUID(),
                "High DB latency", "db-latency", null);
        return incident;
    }

    private NotificationAttempt attempt(Channel channel, String target) {
        return new NotificationAttempt(UUID.randomUUID(), 1, channel, target, Instant.now());
    }

    @Test
    void slack_postsTextToTheConfiguredWebhook() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SLACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.text").exists())
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        new SlackNotificationChannel(builder, SLACK_URL)
                .send(incident(), attempt(Channel.SLACK, "#alerts"));

        server.verify();
    }

    @Test
    void slack_prefersAPerStepWebhookUrlOverTheDefault() throws Exception {
        String perStepUrl = "https://hooks.slack.com/services/T111/B111/yyy";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(perStepUrl)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        new SlackNotificationChannel(builder, SLACK_URL)
                .send(incident(), attempt(Channel.SLACK, perStepUrl));

        server.verify();
    }

    @Test
    void email_postsToResendWithAuthAndRecipient() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.to[0]").value("oncall@example.com"))
                .andExpect(jsonPath("$.subject").value("[Escalade] High DB latency"))
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        new EmailNotificationChannel(builder, "test-key", "Escalade <x@y.com>", "https://api.resend.com")
                .send(incident(), attempt(Channel.EMAIL, "oncall@example.com"));

        server.verify();
    }

    @Test
    void deliveryFailureThrows_soTheWorkerCanRetryAndDeadLetter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SLACK_URL)).andRespond(withServerError());

        SlackNotificationChannel channel = new SlackNotificationChannel(builder, SLACK_URL);

        assertThatThrownBy(() -> channel.send(incident(), attempt(Channel.SLACK, "#alerts")))
                .isInstanceOf(Exception.class);
    }

    @Test
    void channelsOnlyClaimTheirOwnType() {
        RestClient.Builder builder = RestClient.builder();
        SlackNotificationChannel slack = new SlackNotificationChannel(builder, SLACK_URL);
        EmailNotificationChannel email =
                new EmailNotificationChannel(builder, "k", "f", "https://api.resend.com");

        assertThat(slack.supports(Channel.SLACK)).isTrue();
        assertThat(slack.supports(Channel.EMAIL)).isFalse();
        assertThat(email.supports(Channel.EMAIL)).isTrue();
        assertThat(email.supports(Channel.SLACK)).isFalse();
        // The fallback claims everything, which is why it is ordered last.
        assertThat(new LoggingNotificationChannel(0).supports(Channel.WEBHOOK)).isTrue();
    }
}
