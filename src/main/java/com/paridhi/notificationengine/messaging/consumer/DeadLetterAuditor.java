package com.paridhi.notificationengine.messaging.consumer;

import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.service.DeliveryService;
import com.paridhi.notificationengine.service.JsonCodec;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Subscribes to every dead-letter topic and closes the loop in the database.
 *
 * <p>Without it a notification that exhausted its retries would sit at {@code FAILED}
 * forever, indistinguishable from one still waiting on a retry. Marking it
 * {@code DEAD_LETTERED} with the original exception gives operators something queryable:
 * {@code GET /api/v1/notifications?status=DEAD_LETTERED}.
 *
 * <p>The messages stay on the dead-letter topics either way, so they can be replayed onto
 * the source topic once the underlying problem is fixed.
 */
@Component
public class DeadLetterAuditor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAuditor.class);
    private static final String LISTENER_WRAPPER_SEPARATOR = "threw exception;";

    private final DeliveryService deliveryService;
    private final JsonCodec json;

    public DeadLetterAuditor(DeliveryService deliveryService, JsonCodec json) {
        this.deliveryService = deliveryService;
        this.json = json;
    }

    @KafkaListener(
            topics = "#{@kafkaTopics.deadLetterTopics()}",
            groupId = "${notification.kafka.groups.dead-letter:notification-dlt-auditor}")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String reason = describeFailure(record);
        log.error("dead letter on {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);

        try {
            NotificationMessage message = json.read(record.value(), NotificationMessage.class);
            deliveryService.markDeadLettered(message.notificationId(), reason);
        } catch (RuntimeException ex) {
            // A payload that could not be parsed by the consumer cannot be parsed here
            // either. It is already logged above; swallowing keeps the auditor's own
            // offsets moving instead of dead-lettering the dead letter.
            log.warn("dead letter on {} could not be linked to a notification row", record.topic(), ex);
        }
    }

    /**
     * Reads the failure detail the dead-letter publisher stamped onto the record.
     *
     * <p>Spring wraps every listener failure in a {@code ListenerExecutionFailedException},
     * which is noise: the useful part is the cause underneath. This unwraps both the type
     * and the message so the reason stored on the notification names the real problem
     * ("email-provider is temporarily unavailable") rather than the plumbing.
     */
    private String describeFailure(ConsumerRecord<String, String> record) {
        String type = header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (type == null) {
            type = header(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        }
        String message = unwrapMessage(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));

        if (type == null && message == null) {
            return "no failure detail on the record";
        }
        return "%s: %s".formatted(simpleName(type), message);
    }

    /**
     * Strips the wrapper preamble Spring prepends, which reads
     * {@code Listener method '...' threw exception; <the real message>}.
     */
    private String unwrapMessage(String message) {
        if (message == null) {
            return null;
        }
        int separator = message.indexOf(LISTENER_WRAPPER_SEPARATOR);
        return separator < 0
                ? message
                : message.substring(separator + LISTENER_WRAPPER_SEPARATOR.length()).trim();
    }

    private String simpleName(String fqcn) {
        if (fqcn == null) {
            return "unknown error";
        }
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot < 0 ? fqcn : fqcn.substring(lastDot + 1);
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
