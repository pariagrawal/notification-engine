package com.paridhi.notificationengine.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.config.KafkaTopics;
import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.service.JsonCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationRouterTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private JsonCodec json;
    private NotificationRouter router;

    @BeforeEach
    void setUp() {
        json = new JsonCodec(new ObjectMapper());
        router = new NotificationRouter(kafkaTemplate, new KafkaTopics(new NotificationProperties()), json);
    }

    private String payloadFor(Channel channel) {
        return json.write(new NotificationMessage(UUID.randomUUID(), "user-1", channel, "welcome",
                "ada@example.com", "Hi", "Body", Map.of(), Instant.parse("2026-03-10T14:00:00Z")));
    }

    private void sendSucceeds() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "EMAIL, notifications.email",
            "SMS,   notifications.sms",
            "PUSH,  notifications.push",
    })
    void sendsEachChannelToItsOwnTopic(Channel channel, String expectedTopic) {
        sendSucceeds();
        String payload = payloadFor(channel);

        router.route(payload, "user-1");

        verify(kafkaTemplate).send(expectedTopic, "user-1", payload);
    }

    @Test
    void keepsThePartitionKeySoAUsersMessagesStayOrdered() {
        sendSucceeds();

        router.route(payloadFor(Channel.EMAIL), "user-42");

        verify(kafkaTemplate).send(anyString(), org.mockito.ArgumentMatchers.eq("user-42"), anyString());
    }

    @Test
    void forwardsThePayloadUnchanged() {
        // Re-serializing would risk changing the bytes a consumer reads.
        sendSucceeds();
        String payload = payloadFor(Channel.SMS);

        router.route(payload, "user-1");

        verify(kafkaTemplate).send("notifications.sms", "user-1", payload);
    }

    @Test
    void reportsAnUnparseablePayloadAsNonRetryable() {
        // IllegalArgumentException is registered as non-retryable, so a poison message
        // goes straight to the dead-letter topic instead of blocking the partition.
        assertThatThrownBy(() -> router.route("not json at all", "user-1"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void reportsAPayloadWithNoChannelAsNonRetryable() {
        String noChannel = "{\"notificationId\":\"%s\",\"userId\":\"user-1\"}".formatted(UUID.randomUUID());

        assertThatThrownBy(() -> router.route(noChannel, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void failsLoudlyWhenTheBrokerRejectsTheForward() {
        // Swallowing this would silently drop the notification; throwing lets the error
        // handler retry and then dead-letter.
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unreachable")));

        assertThatThrownBy(() -> router.route(payloadFor(Channel.EMAIL), "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notifications.email");
    }

    @Test
    void routesAMessageWhoseMetadataIsAbsent() {
        sendSucceeds();
        String minimal = json.write(new NotificationMessage(UUID.randomUUID(), "user-1", Channel.PUSH,
                "welcome", "device-token-1", null, "Body", null, Instant.now()));

        router.route(minimal, "user-1");

        verify(kafkaTemplate).send("notifications.push", "user-1", minimal);
    }

    @Test
    void roundTripsAMessageThroughJsonWithoutLoss() {
        NotificationMessage original = new NotificationMessage(UUID.randomUUID(), "user-1", Channel.EMAIL,
                "welcome", "ada@example.com", "Hi Ada", "Body", Map.of("campaign", "spring"),
                Instant.parse("2026-03-10T14:00:00Z"));

        assertThat(json.read(json.write(original), NotificationMessage.class)).isEqualTo(original);
    }
}
