package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.config.IgniteCaches;
import com.paridhi.notificationengine.config.NotificationProperties;
import java.time.Duration;
import java.util.UUID;
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
class IdempotencyServiceTest {

    private static final String KEY = "order-42";

    @Mock
    private IgniteCaches caches;

    @Mock
    private ClientCache<String, String> cache;

    /** What {@code withExpirePolicy} hands back; writes go through this one. */
    @Mock
    private ClientCache<String, String> expiringView;

    private NotificationProperties properties;
    private IdempotencyService idempotency;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getIdempotency().setEnabled(true);
        properties.getIdempotency().setTtl(Duration.ofHours(1));
        // doReturn, not when(...).thenReturn: withExpirePolicy re-infers its type
        // parameters, so the fluent form cannot match the typed mock.
        org.mockito.Mockito.doReturn(expiringView).when(cache).withExpirePolicy(any());
        when(caches.idempotency()).thenReturn(java.util.Optional.of(cache));
        idempotency = new IdempotencyService(caches, properties);
    }

    @Test
    void claimSucceedsForAKeyNobodyHasUsed() {
        // getAndPutIfAbsent returns null when it was the one that wrote the key.
        when(expiringView.getAndPutIfAbsent(eq(KEY), anyString())).thenReturn(null);

        assertThat(idempotency.claim(KEY, UUID.randomUUID())).isEmpty();
    }

    @Test
    void claimReturnsTheOriginalNotificationForARepeatedKey() {
        UUID original = UUID.randomUUID();
        when(expiringView.getAndPutIfAbsent(eq(KEY), anyString())).thenReturn(original.toString());

        assertThat(idempotency.claim(KEY, UUID.randomUUID())).contains(original);
    }

    @Test
    void claimDoesNotOverwriteTheExistingValue() {
        // putIfAbsent semantics: the loser of the race must not clobber the winner's id.
        UUID original = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        when(expiringView.getAndPutIfAbsent(eq(KEY), anyString())).thenReturn(original.toString());

        assertThat(idempotency.claim(KEY, loser)).contains(original);
        verify(cache, never()).put(anyString(), anyString());
    }

    @Test
    void claimIgnoresAStoredValueThatIsNotAnId() {
        when(expiringView.getAndPutIfAbsent(eq(KEY), anyString())).thenReturn("not-a-uuid");

        assertThat(idempotency.claim(KEY, UUID.randomUUID())).isEmpty();
    }

    @Test
    void claimAppliesTheConfiguredTimeToLive() {
        // The TTL is attached per write, so a config change reaches a cache that already
        // exists rather than only a freshly created one.
        when(expiringView.getAndPutIfAbsent(anyString(), anyString())).thenReturn(null);

        idempotency.claim(KEY, UUID.randomUUID());

        ArgumentCaptor<ExpiryPolicy> policy = ArgumentCaptor.forClass(ExpiryPolicy.class);
        verify(cache).withExpirePolicy(policy.capture());
        assertThat(policy.getValue()).isInstanceOf(CreatedExpiryPolicy.class);
        assertThat(policy.getValue().getExpiryForCreation().getDurationAmount())
                .isEqualTo(Duration.ofHours(1).toMillis());
    }

    @Test
    void theTimeToLiveIsNotExtendedByLaterWrites() {
        // CreatedExpiryPolicy returns null for update and access, meaning "leave the
        // existing expiry alone" — a claim expires a fixed time after it was taken.
        when(expiringView.getAndPutIfAbsent(anyString(), anyString())).thenReturn(null);
        idempotency.claim(KEY, UUID.randomUUID());

        ArgumentCaptor<ExpiryPolicy> policy = ArgumentCaptor.forClass(ExpiryPolicy.class);
        verify(cache).withExpirePolicy(policy.capture());
        assertThat(policy.getValue().getExpiryForUpdate()).isNull();
        assertThat(policy.getValue().getExpiryForAccess()).isNull();
    }

    @Test
    void releaseDropsTheKeySoTheCallerCanRetry() {
        idempotency.release(KEY);

        verify(cache).remove(KEY);
    }

    @Test
    void skipsTheCacheEntirelyWhenIdempotencyIsOff() {
        properties.getIdempotency().setEnabled(false);
        IdempotencyService disabled = new IdempotencyService(caches, properties);

        assertThat(disabled.claim(KEY, UUID.randomUUID())).isEmpty();
        disabled.release(KEY);

        verifyNoInteractions(expiringView);
        verify(cache, never()).remove(anyString());
    }

    @Test
    void aCacheOutageFallsBackToTheDatabaseConstraintInsteadOfFailingTheRequest() {
        // Proceeding is safe: the unique constraint on notification.idempotency_key still
        // rejects a real duplicate, and ingestion turns that into the original
        // notification. Failing here would turn a cache outage into an ingestion outage.
        when(expiringView.getAndPutIfAbsent(anyString(), anyString()))
                .thenThrow(new ClientException("ignite unavailable"));

        assertThat(idempotency.claim(KEY, UUID.randomUUID())).isEmpty();
    }

    @Test
    void proceedsWhenTheClusterIsUnreachableAtAll() {
        // Not even a client: ingestion must still run, backed by the unique constraint.
        when(caches.idempotency()).thenReturn(java.util.Optional.empty());

        assertThat(idempotency.claim(KEY, UUID.randomUUID())).isEmpty();
    }

    @Test
    void releaseIsANoOpWhenTheClusterIsUnreachable() {
        when(caches.idempotency()).thenReturn(java.util.Optional.empty());

        idempotency.release(KEY);

        verify(cache, never()).remove(anyString());
    }

    @Test
    void releaseToleratesTheCacheBeingDown() {
        // release() runs on a path that is already handling a failure; it must not mask
        // the original error with one of its own.
        org.mockito.Mockito.doThrow(new ClientException("ignite unavailable"))
                .when(cache).remove(anyString());

        idempotency.release(KEY);
    }

    @Test
    void differentKeysDoNotCollide() {
        when(expiringView.getAndPutIfAbsent(anyString(), anyString())).thenReturn(null);
        UUID id = UUID.randomUUID();

        assertThat(idempotency.claim("a", id)).isEmpty();
        assertThat(idempotency.claim("b", id)).isEmpty();

        verify(expiringView).getAndPutIfAbsent(eq("a"), anyString());
        verify(expiringView).getAndPutIfAbsent(eq("b"), anyString());
    }
}
