package com.autolimit.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RateLimitServiceTest {
    private final RateLimitService service = new RateLimitService();

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
        await().atMost(Duration.ofSeconds(2)).until(() -> service.isAllowed("user", 1, 60_000));
        assertThat(service.isAllowed("user", 1, 60_000)).isFalse();
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
        // A zero-length window is already expired when the concurrent requests start.
        assertThat(service.isAllowed("user", 100, 0)).isTrue();

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
                            if (service.isAllowed("user", 100, 60_000)) {
                                accepted++;
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
