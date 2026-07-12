package io.inspector.security.mapping;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.inspector.security.SecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Rung 2: the S3 webhook egress against WireMock. Proves a configured webhook actually POSTs the
 * alert JSON, and that an unconfigured sender is a pure log-only no-op (never touches the network).
 */
class AlertWebhookSenderTest {

    private static final String ENV_REF = "ALERT_WEBHOOK_TEST_URL";

    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private static SecurityProperties props(String webhookRef) {
        return new SecurityProperties(null, null, null, null, null, null, webhookRef);
    }

    @Test
    void aConfiguredWebhookReceivesTheAlertJson() {
        wm.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        Environment env = mock(Environment.class);
        when(env.getProperty(ENV_REF)).thenReturn(wm.baseUrl() + "/hook");

        AlertWebhookSender sender = new AlertWebhookSender(props(ENV_REF), env);
        assertThat(sender.isEnabled()).isTrue();

        sender.post("break-glass-login", "sealed-account login by break-glass");

        // The send is fire-and-forget on a virtual thread — await its arrival, never sleep.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> wm.verify(
                                postRequestedFor(urlEqualTo("/hook"))
                                        .withRequestBody(
                                                equalToJson(
                                                        "{\"event\":\"break-glass-login\",\"detail\":\"sealed-account login by break-glass\"}"))));
    }

    @Test
    void anUnconfiguredSenderIsALogOnlyNoOp() {
        Environment env = mock(Environment.class); // no getProperty stub — ref is null anyway
        AlertWebhookSender sender = new AlertWebhookSender(props(null), env);

        assertThat(sender.isEnabled()).isFalse();
        sender.post("break-glass-login", "detail"); // must not throw, must not hit the network
        wm.verify(0, postRequestedFor(urlEqualTo("/hook")));
    }
}
