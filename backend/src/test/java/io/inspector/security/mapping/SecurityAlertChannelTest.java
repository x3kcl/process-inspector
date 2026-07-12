package io.inspector.security.mapping;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/**
 * Rung 1: every alert fired on the channel is also handed to the webhook egress (S3). The always-on
 * log marker is unconditional; this asserts the added webhook delegation.
 */
class SecurityAlertChannelTest {

    @Test
    void fireDelegatesToTheWebhookInAdditionToTheLogMarker() {
        AlertWebhookSender webhook = mock(AlertWebhookSender.class);
        SecurityAlertChannel channel = new SecurityAlertChannel(webhook);

        channel.fire("access-admin-grant-change", "grant added to group ops-admins");

        verify(webhook).post("access-admin-grant-change", "grant added to group ops-admins");
    }
}
