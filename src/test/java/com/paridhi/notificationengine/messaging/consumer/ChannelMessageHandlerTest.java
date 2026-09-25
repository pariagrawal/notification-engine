package com.paridhi.notificationengine.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.service.DeliveryService;
import com.paridhi.notificationengine.service.JsonCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ChannelMessageHandlerTest {

    @Mock
    private DeliveryService deliveryService;

    private JsonCodec json;
    private ChannelMessageHandler handler;

    @BeforeEach
    void setUp() {
        json = new JsonCodec(new ObjectMapper());
        handler = new ChannelMessageHandler(deliveryService, json);
    }

    private String payloadFor(Channel channel) {
        return json.write(new NotificationMessage(UUID.randomUUID(), "user-1", channel, "welcome",
                "ada@example.com", "Hi", "Body", Map.of(), Instant.parse("2026-03-10T14:00:00Z")));
    }

    @Test
    void handsAMatchingMessageToTheDeliveryService() {
        handler.handle(payloadFor(Channel.EMAIL), Channel.EMAIL);

        ArgumentCaptor<NotificationMessage> delivered = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(deliveryService).deliver(delivered.capture());
        org.assertj.core.api.Assertions.assertThat(delivered.getValue().channel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void refusesAMessageThatLandedOnTheWrongChannelsTopic() {
        // Only reachable if something published straight to a channel topic, bypassing
        // the router. Delivering it would send an SMS body as an email.
        assertThatThrownBy(() -> handler.handle(payloadFor(Channel.SMS), Channel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SMS")
                .hasMessageContaining("EMAIL");

        verify(deliveryService, never()).deliver(any());
    }

    @Test
    void reportsAnUnparseablePayloadAsNonRetryable() {
        assertThatThrownBy(() -> handler.handle("{ not json", Channel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class);

        verify(deliveryService, never()).deliver(any());
    }
}
