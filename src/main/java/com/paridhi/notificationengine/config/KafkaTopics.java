package com.paridhi.notificationengine.config;

import com.paridhi.notificationengine.domain.Channel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for topic names.
 *
 * <p>Exists as a bean so {@code @KafkaListener} annotations can resolve topics through
 * SpEL ({@code "#{@kafkaTopics.inbound()}"}) instead of hard-coding strings that would
 * then have to be kept in sync with the configuration by hand.
 */
@Component
public class KafkaTopics {

    private final NotificationProperties.Kafka config;

    public KafkaTopics(NotificationProperties properties) {
        this.config = properties.getKafka();
    }

    /** Where the outbox publishes and the router listens. */
    public String inbound() {
        return config.getInboundTopic();
    }

    public String forChannel(Channel channel) {
        return config.channelTopic(channel);
    }

    public String deadLetterFor(String sourceTopic) {
        return config.dltTopic(sourceTopic);
    }

    /** Every topic the engine owns, in the order it creates them. */
    public List<String> all() {
        List<String> topics = new ArrayList<>();
        topics.add(inbound());
        topics.add(deadLetterFor(inbound()));
        for (Channel channel : Channel.values()) {
            topics.add(forChannel(channel));
            topics.add(deadLetterFor(forChannel(channel)));
        }
        return topics;
    }

    /** The dead-letter topics, which the auditing listener subscribes to as a set. */
    public String[] deadLetterTopics() {
        return all().stream()
                .filter(topic -> topic.endsWith(config.getDltSuffix()))
                .toArray(String[]::new);
    }
}
