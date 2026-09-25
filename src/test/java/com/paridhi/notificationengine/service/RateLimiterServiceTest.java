package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.config.IgniteCaches;
import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import java.time.Duration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimiterServiceTest {

    @Mock
    private IgniteCaches caches;

    @Mock
    private ClientCache<String, Long> cache;

    /** What {@code withExpirePolicy} hands back; the creating write goes through this. */
    @Mock
    private ClientCache<String, Long> expiringView;

    private NotificationProperties properties;
    private RateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setDefaultLimit(3);
        properties.getRateLimit().setWindow(Duration.ofMinutes(1));
        // doReturn, not when(...).thenReturn: withExpirePolicy re-infers its type
        // parameters, so the fluent form cannot match the typed mock.
        org.mockito.Mockito.doReturn(expiringView).when(cache).withExpirePolicy(any());
        when(caches.rateLimit()).thenReturn(java.util.Optional.of(cache));
        rateLimiter = new RateLimiterService(caches, properties);
    }

    /** The counter already holds {@code current}; the CAS to {@code current + 1} succeeds. */
    private void counterAt(long current) {
        when(cache.get(anyString())).thenReturn(current);
        when(cache.replace(anyString(), eq(current), eq(current + 1))).thenReturn(true);
    }

    @Test
    void theFirstRequestInAWindowCreatesTheCounter() {
        when(cache.get(anyString())).thenReturn(null);
        when(expiringView.putIfAbsent(anyString(), eq(1L))).thenReturn(true);

        RateLimiterService.Decision decision = rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(2);
    }

    @Test
    void allowsRequestsInsideTheQuota() {
        counterAt(1);

        RateLimiterService.Decision decision = rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(3);
        assertThat(decision.remaining()).isEqualTo(1);
        assertThat(decision.retryAfter()).isZero();
    }

    @Test
    void allowsTheRequestThatExactlyReachesTheQuota() {
        counterAt(2);

        RateLimiterService.Decision decision = rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isZero();
    }

    @Test
    void rejectsTheRequestPastTheQuotaAndSaysWhenToRetry() {
        counterAt(3);

        RateLimiterService.Decision decision = rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        // Never longer than the window, and never zero, or a client would hot-loop.
        assertThat(decision.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void retriesTheIncrementWhenAConcurrentRequestWinsTheRace() {
        // First CAS loses (another request incremented in between), the re-read succeeds.
        when(cache.get(anyString())).thenReturn(1L, 2L);
        when(cache.replace(anyString(), eq(1L), eq(2L))).thenReturn(false);
        when(cache.replace(anyString(), eq(2L), eq(3L))).thenReturn(true);

        RateLimiterService.Decision decision = rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isZero();
        verify(cache, atLeast(2)).get(anyString());
    }

    @Test
    void retriesWhenAnotherRequestCreatesTheCounterFirst() {
        when(cache.get(anyString())).thenReturn(null, 1L);
        when(expiringView.putIfAbsent(anyString(), eq(1L))).thenReturn(false);
        when(cache.replace(anyString(), eq(1L), eq(2L))).thenReturn(true);

        assertThat(rateLimiter.tryAcquire("user-1", Channel.EMAIL).allowed()).isTrue();
    }

    @Test
    void givesUpAndAllowsAfterSustainedContention() {
        // Spinning forever on a contended key would cost more than the quota is worth.
        when(cache.get(anyString())).thenReturn(1L);
        when(cache.replace(anyString(), anyLong(), anyLong())).thenReturn(false);

        assertThat(rateLimiter.tryAcquire("user-1", Channel.EMAIL).allowed()).isTrue();
    }

    @Test
    void theWindowExpiryIsSetOnCreationAndNeverExtended() {
        // This is what makes it a fixed window: a steady stream of requests must not keep
        // refreshing the TTL and hold the window open indefinitely.
        when(cache.get(anyString())).thenReturn(null);
        when(expiringView.putIfAbsent(anyString(), eq(1L))).thenReturn(true);

        rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        ArgumentCaptor<ExpiryPolicy> policy = ArgumentCaptor.forClass(ExpiryPolicy.class);
        verify(cache).withExpirePolicy(policy.capture());
        assertThat(policy.getValue()).isInstanceOf(CreatedExpiryPolicy.class);
        assertThat(policy.getValue().getExpiryForCreation().getDurationAmount())
                .isEqualTo(Duration.ofMinutes(1).toMillis());
        assertThat(policy.getValue().getExpiryForUpdate()).isNull();
    }

    @Test
    void theIncrementThatUpdatesAnExistingCounterDoesNotTouchTheExpiry() {
        counterAt(1);

        rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        // The update goes through the plain cache handle, not the expiring view.
        verify(cache).replace(anyString(), eq(1L), eq(2L));
        verify(expiringView, never()).replace(anyString(), anyLong(), anyLong());
    }

    @Test
    void countersAreScopedPerUserPerChannelAndPerWindow() {
        counterAt(1);

        rateLimiter.tryAcquire("user-1", Channel.EMAIL);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(cache).get(key.capture());
        assertThat(key.getValue()).startsWith("user-1:EMAIL:");
    }

    @Test
    void appliesThePerChannelOverrideRatherThanTheDefault() {
        properties.getRateLimit().getLimits().put(Channel.SMS, 1);
        counterAt(1);

        assertThat(rateLimiter.tryAcquire("user-1", Channel.SMS).allowed()).isFalse();
        assertThat(rateLimiter.tryAcquire("user-1", Channel.EMAIL).allowed()).isTrue();
    }

    @Test
    void skipsTheCacheEntirelyWhenRateLimitingIsOff() {
        properties.getRateLimit().setEnabled(false);

        assertThat(rateLimiter.tryAcquire("user-1", Channel.EMAIL).allowed()).isTrue();
        verifyNoInteractions(expiringView);
        verify(cache, never()).get(anyString());
    }

    @Test
    void failsOpenWhenTheClusterIsUnreachableAtAll() {
        // No client at all — the limiter is simply off until Ignite returns.
        when(caches.rateLimit()).thenReturn(java.util.Optional.empty());

        assertThat(rateLimiter.tryAcquire("user-1", Channel.EMAIL).allowed()).isTrue();
        verify(cache, never()).get(anyString());
    }

    @Test
    void failsOpenWhenTheCacheIsUnreachable() {
        // Deliberate trade-off: an outage of the limiter must not stop notifications.
        when(cache.get(anyString())).thenThrow(new ClientException("ignite unavailable"));

        assertThat(rateLimiter.tryAcquire("user-1", Channel.EMAIL).allowed()).isTrue();
    }
}
