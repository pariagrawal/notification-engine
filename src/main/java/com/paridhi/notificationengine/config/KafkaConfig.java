package com.paridhi.notificationengine.config;

import com.paridhi.notificationengine.provider.PermanentDeliveryException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Declares every topic the engine uses, including dead-letter topics.
     *
     * <p>Creating them up front means a dead-letter publish never races topic
     * auto-creation, which is the failure mode where a poison message is dropped instead
     * of parked. Disable with {@code notification.kafka.auto-create-topics=false} where a
     * platform team owns topic provisioning.
     */
    @Bean
    @ConditionalOnProperty(prefix = "notification.kafka", name = "auto-create-topics",
            havingValue = "true", matchIfMissing = true)
    public KafkaAdmin.NewTopics notificationTopics(KafkaTopics topics, NotificationProperties properties) {
        NotificationProperties.Kafka config = properties.getKafka();
        NewTopic[] newTopics = topics.all().stream()
                .map(name -> new NewTopic(name, config.getPartitions(), config.getReplicationFactor()))
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(newTopics);
    }

    /**
     * Blocking retries with exponential backoff, then a dead-letter publish.
     *
     * <p>Spring Boot wires this bean into the auto-configured listener container factory,
     * so every {@code @KafkaListener} in the application inherits it.
     *
     * <p>Two classes of failure skip the retries entirely, because repeating them can only
     * waste time and hold up the partition: a message that cannot be deserialized, and a
     * {@link PermanentDeliveryException} from a provider (an unroutable address, a
     * rejected token). Those go straight to the dead-letter topic.
     */
    @Bean
    public CommonErrorHandler notificationErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                       KafkaTopics topics,
                                                       NotificationProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // -1 lets the producer partition the dead letter by key rather than
                // assuming the DLT has at least as many partitions as the source topic.
                (record, exception) -> new TopicPartition(topics.deadLetterFor(record.topic()), -1));

        NotificationProperties.Retry retry = properties.getRetry();
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(retry.getInitialBackoff().toMillis());
        backOff.setMultiplier(retry.getMultiplier());
        backOff.setMaxInterval(retry.getMaxBackoff().toMillis());
        // maxAttempts counts deliveries; the backoff only governs the retries after the first.
        backOff.setMaxAttempts(Math.max(0, retry.getMaxAttempts() - 1));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                PermanentDeliveryException.class,
                DeserializationException.class,
                ConversionException.class,
                IllegalArgumentException.class);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("delivery attempt {} failed for {}-{}@{}: {}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(),
                        exception.toString()));
        return errorHandler;
    }
}
