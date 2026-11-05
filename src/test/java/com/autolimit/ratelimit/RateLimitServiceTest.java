package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {
    private final FakeTicker ticker = new FakeTicker();
    private final RateLimitService service = new RateLimitService(ticker, 3);

    @Test
    void retryDelayUsesOriginalBoundaryAndRoundsUpWithoutChargingRejectedAttempts() {
        assertThat(service.check("user", 2, 2500).allowed()).isTrue();
        ticker.advance(Duration.ofMillis(500));
        assertThat(service.check("user", 2, 2500).allowed()).isTrue();
        assertThat(service.check("user", 2, 2500).retryAfterSeconds()).hasValue(2);
        ticker.advance(Duration.ofMillis(999));
        assertThat(service.check("user", 2, 2500).retryAfterSeconds()).hasValue(2);
        ticker.advance(Duration.ofMillis(1));
        assertThat(service.check("user", 2, 2500).retryAfterSeconds()).hasValue(1);
        ticker.advance(Duration.ofMillis(1000).minusNanos(1));
        assertThat(service.check("user", 2, 2500).retryAfterSeconds()).hasValue(1);
        ticker.advance(Duration.ofNanos(1));
        var renewed = service.check("user", 2, 2500);
        assertThat(renewed.allowed()).isTrue();
        assertThat(renewed.retryAfterSeconds()).isEmpty();
        assertThat(service.isAllowed("user", 2, 2500)).isTrue();
        assertThat(service.check("user", 2, 2500).retryAfterSeconds()).hasValue(3);
    }

    @Test
    void firstRequestAnchoredWindowsAllowBoundaryBurstsWithoutSlidingTheDeadline() {
        assertThat(service.isAllowed("burst", 5, 60_000)).isTrue();
        ticker.advance(Duration.ofSeconds(60).minusNanos(1));
        for (int i = 0; i < 4; i++) assertThat(service.isAllowed("burst", 5, 60_000)).isTrue();
        assertThat(service.check("burst", 5, 60_000).retryAfterSeconds()).hasValue(1);
        ticker.advance(Duration.ofNanos(1));
        for (int i = 0; i < 5; i++) assertThat(service.isAllowed("burst", 5, 60_000)).isTrue();
        assertThat(service.check("burst", 5, 60_000).retryAfterSeconds()).hasValue(60);
        // Nine requests were admitted across the boundary; this is not a rolling quota.
    }

    @Test
    void capacityAndZeroQuotasHaveNoPromisedRetryDelay() {
        var limiter = new RateLimitService(ticker, 1);
        assertThat(limiter.check("zero", 0, 100).retryAfterSeconds()).isEmpty();
        assertThat(limiter.check("zero", 0, 100).allowed()).isFalse();
        assertThat(limiter.check("alice", 1, 100).allowed()).isTrue();
        var capacity = limiter.check("bob", 1, 100);
        assertThat(capacity.allowed()).isFalse();
        assertThat(capacity.retryAfterSeconds()).isEmpty();
        assertThat(limiter.check("alice", 1, 100).retryAfterSeconds()).hasValue(1);
    }

    @Test
    void independentLimiterInstancesDoNotShareCounters() {
        var other = new RateLimitService(ticker, 3);
        assertThat(service.isAllowed("same-key", 1, 100)).isTrue();
        assertThat(service.isAllowed("same-key", 1, 100)).isFalse();
        assertThat(other.isAllowed("same-key", 1, 100)).isTrue();
        assertThat(other.isAllowed("same-key", 1, 100)).isFalse();
    }

    @Test
    void hourlyQuotaSurvivesMoreThanFifteenMinutesIdle() {
        assertThat(service.isAllowed("hourly", 1, 3_600_000)).isTrue();
        ticker.advance(Duration.ofMinutes(16));
        assertThat(service.isAllowed("hourly", 1, 3_600_000)).isFalse();
        ticker.advance(Duration.ofMinutes(44).minusNanos(1));
        assertThat(service.isAllowed("hourly", 1, 3_600_000)).isFalse();
        ticker.advance(Duration.ofNanos(1));
        assertThat(service.isAllowed("hourly", 1, 3_600_000)).isTrue();
    }

    @Test
    void capacityRejectsNewKeysWithoutEvictingExistingQuotas() {
        var limiter = new RateLimitService(ticker, 1);
        assertThat(limiter.isAllowed("alice", 2, 100)).isTrue();
        assertThat(limiter.isAllowed("bob", 2, 100)).isFalse();
        assertThat(limiter.isAllowed("alice", 2, 100)).isTrue();
        assertThat(limiter.isAllowed("alice", 2, 100)).isFalse();
    }

    @Test
    void expiredKeysReleaseCapacityAtTheWindowBoundary() {
        var limiter = new RateLimitService(ticker, 1);
        assertThat(limiter.isAllowed("alice", 1, 100)).isTrue();
        ticker.advance(Duration.ofMillis(100).minusNanos(1));
        assertThat(limiter.isAllowed("bob", 1, 100)).isFalse();
        ticker.advance(Duration.ofNanos(1));
        assertThat(limiter.isAllowed("bob", 1, 100)).isTrue();
        assertThat(limiter.isAllowed("carol", 1, 100)).isFalse();
    }

    @Test
    void zeroQuotaDoesNotConsumeCapacity() {
        var limiter = new RateLimitService(ticker, 1);
        assertThat(limiter.isAllowed("alice", 0, 100)).isFalse();
        assertThat(limiter.isAllowed("bob", 1, 100)).isTrue();
    }

    @Test
    void expiredKeyCanRenewWhileCapacityIsFull() {
        var limiter = new RateLimitService(ticker, 1);
        assertThat(limiter.isAllowed("alice", 1, 100)).isTrue();
        ticker.advance(Duration.ofMillis(100));
        assertThat(limiter.isAllowed("alice", 1, 100)).isTrue();
        assertThat(limiter.isAllowed("alice", 1, 100)).isFalse();
        assertThat(limiter.isAllowed("bob", 1, 100)).isFalse();
    }

    @Test
    void reclaimingExpiredCapacityPreservesOtherActiveQuotas() {
        var limiter = new RateLimitService(ticker, 2);
        assertThat(limiter.isAllowed("short", 1, 10)).isTrue();
        assertThat(limiter.isAllowed("long", 2, 100)).isTrue();
        ticker.advance(Duration.ofMillis(10));
        assertThat(limiter.isAllowed("new", 1, 100)).isTrue();
        assertThat(limiter.isAllowed("long", 2, 100)).isTrue();
        assertThat(limiter.isAllowed("long", 2, 100)).isFalse();
        assertThat(limiter.isAllowed("another", 1, 100)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidCapacity(int capacity) {
        assertThatThrownBy(() -> new RateLimitService(capacity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitService(ticker, capacity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullKeyAndTickerAndNegativeQuota() {
        assertThatThrownBy(() -> new RateLimitService(null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.isAllowed(null, 1, 100)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.isAllowed("user", -1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 31_536_000_001L, Long.MAX_VALUE})
    void rejectsInvalidWindow(long windowMillis) {
        assertThatThrownBy(() -> service.isAllowed("user", 1, windowMillis))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 31_536_000_000L})
    void acceptsWindowBounds(long windowMillis) {
        assertThat(service.isAllowed("user", 1, windowMillis)).isTrue();
        ticker.advance(Duration.ofMillis(windowMillis).minusNanos(1));
        assertThat(service.isAllowed("user", 1, windowMillis)).isFalse();
        ticker.advance(Duration.ofNanos(1));
        assertThat(service.isAllowed("user", 1, windowMillis)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void concurrentDistinctKeysNeverExceedCapacityIncludingAfterExpiry(int capacity) throws Exception {
        int workers = 16;
        var limiter = new RateLimitService(ticker, capacity);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int round = 0; round < 25; round++) {
                ticker.advance(Duration.ofMillis(100));
                CountDownLatch ready = new CountDownLatch(workers);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                String prefix = "round-" + round + ":";
                try {
                    for (int i = 0; i < workers; i++) {
                        String key = prefix + i;
                        results.add(executor.submit(() -> {
                            ready.countDown();
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Concurrent requests did not start in time");
                            }
                            return limiter.isAllowed(key, 1, 100);
                        }));
                    }
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                } finally {
                    start.countDown();
                }
                int accepted = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) accepted++;
                }
                assertThat(accepted).as("distinct keys admitted in round %s", round).isEqualTo(capacity);
                for (int i = 0; i < workers; i++) {
                    assertThat(limiter.isAllowed(prefix + i, 1, 100)).isFalse();
                }
            }
        }
    }

    @Test
    void allowsUpToTheLimitAndRejectsFurtherRequests() {
        assertThat(service.isAllowed("user:endpoint", 2, 60_000)).isTrue();
        assertThat(service.isAllowed("user:endpoint", 2, 60_000)).isTrue();
        assertThat(service.isAllowed("user:endpoint", 2, 60_000)).isFalse();
        assertThat(service.isAllowed("user:endpoint", 2, 60_000)).isFalse();
    }

    @Test
    void tracksUsersAndEndpointsSeparately() {
        assertThat(service.isAllowed("alice:read", 1, 60_000)).isTrue();
        assertThat(service.isAllowed("alice:read", 1, 60_000)).isFalse();
        assertThat(service.isAllowed("bob:read", 1, 60_000)).isTrue();
        assertThat(service.isAllowed("alice:write", 1, 60_000)).isTrue();
    }

    @Test
    void permitsRequestsAgainAfterWindowExpires() {
        assertThat(service.isAllowed("user", 1, 30)).isTrue();
        ticker.advance(Duration.ofMillis(30).minusNanos(1));
        assertThat(service.isAllowed("user", 1, 30)).isFalse();

        ticker.advance(Duration.ofNanos(1));
        assertThat(service.isAllowed("user", 1, 60_000)).isTrue();
        assertThat(service.isAllowed("user", 1, 60_000)).isFalse();
    }

    @Test
    void cacheExpiresOnTheSameClockWithoutExtendingTheWindow() {
        // Inspect cache visibility before another limiter call can reset the window.
        var cache = (Cache<?, ?>) ReflectionTestUtils.getField(service, "cache");
        assertThat(cache).isNotNull();
        assertThat(service.isAllowed("user", 2, 100)).isTrue();

        ticker.advance(Duration.ofMillis(40));
        assertThat(service.isAllowed("user", 2, 100)).isTrue();
        ticker.advance(Duration.ofMillis(40));
        assertThat(service.isAllowed("user", 2, 100)).isFalse();

        ticker.advance(Duration.ofMillis(20).minusNanos(1));
        assertThat(cache.asMap().get("user")).isNotNull();
        ticker.advance(Duration.ofNanos(1));
        assertThat(cache.asMap().get("user")).isNull();

        assertThat(service.isAllowed("user", 2, 100)).isTrue();
        assertThat(service.isAllowed("user", 2, 100)).isTrue();
        assertThat(service.isAllowed("user", 2, 100)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 25})
    void concurrentRequestsConsumeExactlyTheRemainingQuota(int alreadyAccepted) throws Exception {
        for (int i = 0; i < alreadyAccepted; i++) {
            assertThat(service.isAllowed("user", 100, 60_000)).isTrue();
        }

        assertConcurrentAcceptedCount(100 - alreadyAccepted);
    }

    @Test
    void concurrentRequestsResetAnExpiredWindowOnlyOnce() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(service.isAllowed("user", 100, 60_000)).isTrue();
        }
        assertThat(service.isAllowed("user", 100, 60_000)).isFalse();
        ticker.advance(Duration.ofMinutes(1));

        assertConcurrentAcceptedCount(100);
    }

    @Test
    void rejectedRequestsDoNotIncreaseTheCounter() {
        assertThat(service.isAllowed("user", 1, 60_000)).isTrue();
        assertThat(service.isAllowed("user", 1, 60_000)).isFalse();
        assertThat(service.isAllowed("user", 1, 60_000)).isFalse();

        assertThat(service.isAllowed("user", 3, 60_000)).isTrue();
        assertThat(service.isAllowed("user", 3, 60_000)).isTrue();
        assertThat(service.isAllowed("user", 3, 60_000)).isFalse();
    }

    @Test
    void zeroQuotaRejectsTheFirstRequest() {
        assertThat(service.isAllowed("user", 0, 60_000)).isFalse();
    }

    private static final class FakeTicker implements Ticker {
        // An arbitrary monotonic origin exposes accidental use of the system clock.
        private final AtomicLong nanos = new AtomicLong(Duration.ofDays(7).toNanos());

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    private void assertConcurrentAcceptedCount(int expectedAccepted) throws Exception {
        int workers = 32;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(workers)) {
            try {
                for (int i = 0; i < workers; i++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent requests did not start in time");
                        }
                        int accepted = 0;
                        for (int request = 0; request < 32; request++) {
                            var decision = service.check("user", 100, 60_000);
                            if (decision.allowed()) {
                                accepted++;
                            } else {
                                assertThat(decision.retryAfterSeconds()).hasValue(60);
                            }
                        }
                        return accepted;
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).as("all workers are ready").isTrue();
            } finally {
                start.countDown();
            }

            int accepted = 0;
            for (Future<Integer> result : results) {
                accepted += result.get(10, TimeUnit.SECONDS);
            }
            assertThat(accepted).isEqualTo(expectedAccepted);
            assertThat(service.isAllowed("user", 100, 60_000)).isFalse();
        }
    }
}
