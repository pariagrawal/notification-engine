package com.paridhi.notificationengine.config;

import com.paridhi.notificationengine.domain.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything tunable about the engine, under the {@code notification.*} prefix. */
@ConfigurationProperties(prefix = "notification")
@Getter
@Setter
public class NotificationProperties {

    private final Ignite ignite = new Ignite();
    private final Kafka kafka = new Kafka();
    private final Outbox outbox = new Outbox();
    private final RateLimit rateLimit = new RateLimit();
    private final Idempotency idempotency = new Idempotency();
    private final Retry retry = new Retry();
    private final Preferences preferences = new Preferences();
    private final Providers providers = new Providers();

    /** Connection settings for the Ignite cluster backing idempotency and rate limiting. */
    @Getter
    @Setter
    public static class Ignite {

        /** Thin-client endpoints, {@code host:port}. List every server node. */
        private List<String> addresses = new ArrayList<>(List.of("localhost:10800"));

        /** Socket timeout for a single cache operation. */
        private Duration timeout = Duration.ofSeconds(2);

        /**
         * Routes each request straight to the node owning the key instead of through an
         * arbitrary one, which removes a network hop per operation.
         */
        private boolean partitionAware = true;

        /** Client-side retries for a request that fails on a recoverable connection error. */
        private int retryLimit = 2;

        /**
         * How long to wait before retrying a failed connection. Stops every request from
         * paying a full connection timeout while the cluster is down.
         */
        private Duration reconnectCooldown = Duration.ofSeconds(10);

        /** Cache holding claimed idempotency keys. */
        private String idempotencyCache = "notification-idempotency";

        /** Cache holding per-user, per-window request counters. */
        private String rateLimitCache = "notification-rate-limit";

        /**
         * Copies of each cache entry kept on other nodes. Zero is right for a single-node
         * dev cluster; raise it in production or a node loss drops live counters and
         * in-flight idempotency claims.
         */
        private int backups = 1;
    }

    @Getter
    @Setter
    public static class Kafka {

        /** Topic the outbox publishes to and the router consumes from. */
        private String inboundTopic = "notifications.inbound";

        /** Prefix for the per-channel topics, e.g. {@code notifications.} + {@code email}. */
        private String channelTopicPrefix = "notifications.";

        /** Suffix the dead-letter publisher appends to a channel topic. */
        private String dltSuffix = ".DLT";

        private int partitions = 3;
        private short replicationFactor = 1;

        /** Whether the application should create its topics at startup. */
        private boolean autoCreateTopics = true;

        private final Groups groups = new Groups();

        /**
         * Listener threads per channel, keyed by the lowercase channel name
         * ({@code notification.kafka.concurrency.email=4}). Never set it above the
         * topic's partition count — the extra consumers would sit idle.
         */
        private Map<String, Integer> concurrency = new java.util.LinkedHashMap<>();

        public String channelTopic(Channel channel) {
            return channelTopicPrefix + channel.topicSegment();
        }

        public String dltTopic(String sourceTopic) {
            return sourceTopic + dltSuffix;
        }

        /**
         * Consumer group ids. Each stage gets its own group so the router, the three
         * channels, and the dead-letter auditor scale and lag independently.
         */
        @Getter
        @Setter
        public static class Groups {

            private String router = "notification-router";
            private String email = "notification-email";
            private String sms = "notification-sms";
            private String push = "notification-push";
            private String deadLetter = "notification-dlt-auditor";
        }
    }

    @Getter
    @Setter
    public static class Outbox {

        private boolean enabled = true;

        /** Delay between the end of one poll and the start of the next. */
        private Duration pollDelay = Duration.ofMillis(500);

        /** Rows claimed per poll. */
        private int batchSize = 100;

        /** Publish failures tolerated before the row is parked as FAILED. */
        private int maxAttempts = 10;

        /** How long to keep PUBLISHED rows before the cleanup job deletes them. */
        private Duration retention = Duration.ofDays(7);

        /** When the retention sweep runs. Hourly, on the hour. */
        private String cleanupCron = "0 0 * * * *";
    }

    @Getter
    @Setter
    public static class RateLimit {

        private boolean enabled = true;

        /** Length of the fixed window each user's counter is bucketed into. */
        private Duration window = Duration.ofMinutes(1);

        /** Requests per user per window when the channel has no explicit override. */
        private int defaultLimit = 20;

        /** Per-channel overrides, e.g. {@code notification.rate-limit.limits.sms=5}. */
        private Map<Channel, Integer> limits = new EnumMap<>(Channel.class);

        public int limitFor(Channel channel) {
            return limits.getOrDefault(channel, defaultLimit);
        }
    }

    @Getter
    @Setter
    public static class Idempotency {

        private boolean enabled = true;

        /** How long a de-duplication key is remembered in the cache. */
        private Duration ttl = Duration.ofHours(24);
    }

    @Getter
    @Setter
    public static class Retry {

        /** Delivery attempts before a record is dead-lettered, including the first. */
        private int maxAttempts = 4;

        private Duration initialBackoff = Duration.ofSeconds(1);
        private double multiplier = 2.0;
        private Duration maxBackoff = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Preferences {

        /** Treatment of a channel a user has no preference row for. */
        private boolean defaultEnabled = true;

        private String defaultLocale = "en";
        private String defaultTimeZone = "UTC";
    }

    @Getter
    @Setter
    public static class Providers {

        /**
         * Fraction of provider calls the built-in simulated providers fail with a
         * retryable error. Exists to exercise the retry and dead-letter paths in a demo;
         * keep it at 0 for anything resembling real use.
         */
        private double simulatedFailureRate = 0.0;

        /** Artificial latency the simulated providers add to each call. */
        private Duration simulatedLatency = Duration.ZERO;
    }
}
