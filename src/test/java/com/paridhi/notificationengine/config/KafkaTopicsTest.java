package com.paridhi.notificationengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.paridhi.notificationengine.domain.Channel;
import org.junit.jupiter.api.Test;

class KafkaTopicsTest {

    private final KafkaTopics topics = new KafkaTopics(new NotificationProperties());

    @Test
    void namesTheInboundTopic() {
        assertThat(topics.inbound()).isEqualTo("notifications.inbound");
    }

    @Test
    void namesOneTopicPerChannel() {
        assertThat(topics.forChannel(Channel.EMAIL)).isEqualTo("notifications.email");
        assertThat(topics.forChannel(Channel.SMS)).isEqualTo("notifications.sms");
        assertThat(topics.forChannel(Channel.PUSH)).isEqualTo("notifications.push");
    }

    @Test
    void derivesDeadLetterTopicsFromTheirSource() {
        // Must match what DeadLetterPublishingRecoverer is configured to produce, or
        // dead letters would land on a topic nobody reads.
        assertThat(topics.deadLetterFor("notifications.email")).isEqualTo("notifications.email.DLT");
    }

    @Test
    void declaresEveryTopicTheEngineNeeds() {
        assertThat(topics.all()).containsExactlyInAnyOrder(
                "notifications.inbound", "notifications.inbound.DLT",
                "notifications.email", "notifications.email.DLT",
                "notifications.sms", "notifications.sms.DLT",
                "notifications.push", "notifications.push.DLT");
    }

    @Test
    void addingAChannelAddsBothOfItsTopics() {
        // Two topics per channel plus the inbound pair; a new enum constant must not need
        // a matching edit here.
        assertThat(topics.all()).hasSize(2 + Channel.values().length * 2);
    }

    @Test
    void theAuditorSubscribesToEveryDeadLetterTopicAndNothingElse() {
        assertThat(topics.deadLetterTopics())
                .hasSize(1 + Channel.values().length)
                .allSatisfy(topic -> assertThat(topic).endsWith(".DLT"));
    }

    @Test
    void honoursCustomTopicNaming() {
        NotificationProperties custom = new NotificationProperties();
        custom.getKafka().setInboundTopic("acme.notify.in");
        custom.getKafka().setChannelTopicPrefix("acme.notify.");
        custom.getKafka().setDltSuffix("-dead");

        KafkaTopics renamed = new KafkaTopics(custom);

        assertThat(renamed.inbound()).isEqualTo("acme.notify.in");
        assertThat(renamed.forChannel(Channel.SMS)).isEqualTo("acme.notify.sms");
        assertThat(renamed.deadLetterTopics()).allSatisfy(topic -> assertThat(topic).endsWith("-dead"));
    }
}
