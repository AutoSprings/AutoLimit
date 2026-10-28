package com.autolimit.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
        await().atMost(Duration.ofSeconds(2)).until(() -> service.isAllowed("user", 1, 30));
    }

    @Test
    void zeroQuotaRejectsTheFirstRequest() {
        assertThat(service.isAllowed("user", 0, 60_000)).isFalse();
    }
}
