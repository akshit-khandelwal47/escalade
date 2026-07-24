package dev.escalade.channel;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shared HTTP client for outbound notification transports.
 *
 * <p>The timeouts are not optional. A delivery runs inside the transaction that holds the attempt's
 * row lock, so an unresponsive Slack or email endpoint would otherwise pin that lock — and the
 * incident's escalation — for as long as the socket stayed open. Bounding the call means the worst
 * case is a failed attempt that retries, which the dead-letter path already handles.
 *
 * <p>This is the pragmatic version of the trade-off. The scale-out fix is to claim an attempt, commit
 * an in-flight marker, and deliver outside the lock with a visibility timeout to recover crashed
 * workers. Bounded timeouts are sufficient while a tick is short.
 */
@Configuration
public class ChannelHttpConfig {

    @Bean
    @Scope("prototype")
    public RestClient.Builder channelRestClientBuilder(
            @Value("${escalade.channels.http.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${escalade.channels.http.read-timeout-ms:5000}") long readTimeoutMs) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInitializer(request -> request.getHeaders().add("User-Agent", "escalade/0.1"));
    }
}
