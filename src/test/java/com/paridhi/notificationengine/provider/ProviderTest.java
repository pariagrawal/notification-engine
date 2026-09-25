package com.paridhi.notificationengine.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderTest {

    private final NotificationProperties properties = new NotificationProperties();

    private NotificationMessage messageTo(Channel channel, String recipient) {
        return new NotificationMessage(UUID.randomUUID(), "user-1", channel, "welcome",
                recipient, "Hi", "Body", Map.of(), Instant.parse("2026-03-10T14:00:00Z"));
    }

    @Test
    void emailProviderAcceptsAWellFormedAddress() {
        DeliveryResult result = new EmailProvider(properties).send(messageTo(Channel.EMAIL, "ada@example.com"));

        assertThat(result.providerMessageId()).startsWith("email-provider-");
        assertThat(result.latencyMs()).isNotNegative();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "ada@", "@example.com", "ada@example", "ada example@x.com"})
    void emailProviderRejectsAnUndeliverableAddressWithoutRetrying(String address) {
        // Permanent, not transient: no number of retries makes a malformed address valid.
        assertThatThrownBy(() -> new EmailProvider(properties).send(messageTo(Channel.EMAIL, address)))
                .isInstanceOf(PermanentDeliveryException.class);
    }

    @Test
    void smsProviderAcceptsAnE164Number() {
        assertThat(new SmsProvider(properties).send(messageTo(Channel.SMS, "+14155550123")).providerMessageId())
                .startsWith("sms-provider-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4155550123", "+0155550123", "+1415", "+1415555012345678", "not-a-number"})
    void smsProviderRejectsAnythingThatIsNotE164(String number) {
        assertThatThrownBy(() -> new SmsProvider(properties).send(messageTo(Channel.SMS, number)))
                .isInstanceOf(PermanentDeliveryException.class);
    }

    @Test
    void pushProviderRejectsATokenTooShortToBeReal() {
        assertThatThrownBy(() -> new PushProvider(properties).send(messageTo(Channel.PUSH, "abc")))
                .isInstanceOf(PermanentDeliveryException.class);
    }

    @Test
    void anyProviderRejectsAMessageWithNoRecipient() {
        assertThatThrownBy(() -> new EmailProvider(properties).send(messageTo(Channel.EMAIL, null)))
                .isInstanceOf(PermanentDeliveryException.class)
                .hasMessageContaining("no recipient");
        assertThatThrownBy(() -> new SmsProvider(properties).send(messageTo(Channel.SMS, "  ")))
                .isInstanceOf(PermanentDeliveryException.class);
    }

    @Test
    void injectedFailuresAreRetryableSoTheyExerciseTheRetryPath() {
        properties.getProviders().setSimulatedFailureRate(0.5);
        // A draw below the rate fails; the same provider succeeds on a draw above it.
        EmailProvider alwaysFails = failingAt(0.1);
        EmailProvider neverFails = failingAt(0.9);

        assertThatThrownBy(() -> alwaysFails.send(messageTo(Channel.EMAIL, "ada@example.com")))
                .isInstanceOf(TransientDeliveryException.class);
        assertThat(neverFails.send(messageTo(Channel.EMAIL, "ada@example.com"))).isNotNull();
    }

    @Test
    void noFailuresAreInjectedWhenTheRateIsZero() {
        properties.getProviders().setSimulatedFailureRate(0.0);

        assertThat(failingAt(0.0).send(messageTo(Channel.EMAIL, "ada@example.com"))).isNotNull();
    }

    /** An EmailProvider whose "random" draw is fixed, so failure injection is testable. */
    private EmailProvider failingAt(double draw) {
        return new EmailProvider(properties) {
            @Override
            public DeliveryResult send(NotificationMessage message) {
                return new FixedDrawEmailProvider(properties, draw).send(message);
            }
        };
    }

    /** Minimal subclass that pins the random draw used for failure injection. */
    private static final class FixedDrawEmailProvider extends SimulatedProvider {

        private FixedDrawEmailProvider(NotificationProperties properties, double draw) {
            super(properties.getProviders(), () -> draw);
        }

        @Override
        public Channel channel() {
            return Channel.EMAIL;
        }

        @Override
        public String name() {
            return "email-provider";
        }

        @Override
        protected void validateRecipient(String recipient) {
            // Address validity is covered by EmailProvider's own tests.
        }
    }

    @Test
    void registryResolvesTheProviderForEachChannel() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new EmailProvider(properties), new SmsProvider(properties), new PushProvider(properties)));

        assertThat(registry.require(Channel.EMAIL).name()).isEqualTo("email-provider");
        assertThat(registry.require(Channel.SMS).name()).isEqualTo("sms-provider");
        assertThat(registry.require(Channel.PUSH).name()).isEqualTo("push-provider");
    }

    @Test
    void registryRefusesAChannelWithNoProvider() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new EmailProvider(properties)));

        assertThatThrownBy(() -> registry.require(Channel.SMS))
                .isInstanceOf(PermanentDeliveryException.class);
    }

    @Test
    void registryRefusesTwoProvidersForTheSameChannel() {
        // Silently picking one would make delivery depend on bean ordering.
        assertThatThrownBy(() -> new ProviderRegistry(
                List.of(new EmailProvider(properties), new EmailProvider(properties))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL");
    }
}
