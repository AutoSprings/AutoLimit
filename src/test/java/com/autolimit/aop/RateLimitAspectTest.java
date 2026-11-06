package com.autolimit.aop;

import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.exception.RateLimitExceededException;
import com.autolimit.ratelimit.RateLimitService;
import com.autolimit.ratelimit.RateLimitDecision;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;
import com.autolimit.identity.TrustedProxyClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitAspectTest {
    private final RateLimitService limiter = mock(RateLimitService.class);
    private final CallerIdentityResolver identityResolver = mock(CallerIdentityResolver.class);
    private LimitedService service;

    @BeforeEach
    void setUp() {
        when(identityResolver.resolve()).thenReturn(Optional.empty());
        when(limiter.check(anyString(), anyInt(), anyLong())).thenReturn(RateLimitDecision.deny());
        AspectJProxyFactory factory = new AspectJProxyFactory(new LimitedService());
        factory.addAdvisor(new RateLimitAspect(limiter, identityResolver,
                new TrustedProxyClientIpResolver(List.of(), 8192, 32)));
        service = factory.getProxy();
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void convertsSupportedDurationsExactlyForEveryUnit() {
        for (TimeUnit unit : TimeUnit.values()) {
            long oneSecond = unit.convert(1, TimeUnit.SECONDS);
            if (oneSecond > 0) {
                assertThat(RateLimitAspect.windowMillis(oneSecond, unit)).isEqualTo(1000);
            }
            long year = unit.convert(365, TimeUnit.DAYS);
            assertThat(RateLimitAspect.windowMillis(year, unit)).isEqualTo(31_536_000_000L);
        }
        assertThat(RateLimitAspect.windowMillis(1, TimeUnit.MILLISECONDS)).isEqualTo(1);
    }

    @Test
    void rejectsOverflowNonpositiveAndOutOfRangeDurationsForEveryUnit() {
        for (TimeUnit unit : TimeUnit.values()) {
            for (long invalid : new long[] {-1, 0, Long.MAX_VALUE, unit.convert(366, TimeUnit.DAYS)}) {
                assertThatThrownBy(() -> RateLimitAspect.windowMillis(invalid, unit))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        for (long invalid : new long[] {1, 999, 1001}) {
            assertThatThrownBy(() -> RateLimitAspect.windowMillis(invalid, TimeUnit.MICROSECONDS))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RateLimitAspect.windowMillis(1_000_001, TimeUnit.NANOSECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAnnotationDurationFailsBeforeQuotaConsumption() {
        assertThatThrownBy(service::invalidWindow).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(limiter);
    }

    @Test
    void choosesHighestMatchingRoleQuotaCaseInsensitively() {
        resolveCaller("alice", "premium", "ENTERPRISE");
        when(limiter.check(key("user:alice", "tiered"), 20, 30_000)).thenReturn(RateLimitDecision.permit());
        assertThat(service.tiered()).isEqualTo("ok");
        verify(limiter).check(key("user:alice", "tiered"), 20, 30_000);
    }

    @Test
    void unmatchedRolesUseFallbackQuota() {
        resolveCaller("alice", "USER");
        when(limiter.check(key("user:alice", "tiered"), 2, 30_000)).thenReturn(RateLimitDecision.permit());
        service.tiered();
        verify(limiter).check(key("user:alice", "tiered"), 2, 30_000);
    }

    @Test
    void callerWithoutRolesUsesUserKeyAndFallbackQuota() {
        resolveCaller("bob");
        when(limiter.check(key("user:bob", "tiered"), 2, 30_000)).thenReturn(RateLimitDecision.permit());
        service.tiered();
        verify(limiter).check(key("user:bob", "tiered"), 2, 30_000);
    }

    @Test
    void resolvesCallerForEachInvocation() {
        when(identityResolver.resolve())
                .thenReturn(Optional.of(new CallerIdentity("alice", List.of("PREMIUM"))))
                .thenReturn(Optional.of(new CallerIdentity("bob", List.of("ENTERPRISE"))));
        when(limiter.check(key("user:alice", "tiered"), 10, 30_000)).thenReturn(RateLimitDecision.permit());
        when(limiter.check(key("user:bob", "tiered"), 20, 30_000)).thenReturn(RateLimitDecision.permit());
        service.tiered();
        service.tiered();
        verify(limiter).check(key("user:alice", "tiered"), 10, 30_000);
        verify(limiter).check(key("user:bob", "tiered"), 20, 30_000);
        verify(identityResolver, times(2)).resolve();
    }

    @Test
    void anonymousRequestsIgnoreUntrustedForwardedIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.check(key("ip:198.51.100.20", "tiered"), 2, 30_000)).thenReturn(RateLimitDecision.permit());
        service.tiered();
        verify(limiter).check(key("ip:198.51.100.20", "tiered"), 2, 30_000);
    }

    @Test
    void anonymousRequestsFallBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.11");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.check(key("ip:203.0.113.11", "tiered"), 2, 30_000)).thenReturn(RateLimitDecision.permit());
        service.tiered();
        verify(limiter).check(key("ip:203.0.113.11", "tiered"), 2, 30_000);
    }

    @Test
    void exceededQuotaPreventsMethodInvocation() {
        assertThatThrownBy(service::tiered).isInstanceOf(RateLimitExceededException.class);
        verify(limiter).check(key("ip:unknown", "tiered"), 2, 30_000);
    }

    @Test
    void rejectedWindowCarriesTheAtomicRetryDelay() {
        when(limiter.check(key("ip:unknown", "tiered"), 2, 30_000)).thenReturn(RateLimitDecision.deny(7));
        assertThatThrownBy(service::tiered).isInstanceOfSatisfying(RateLimitExceededException.class,
                failure -> assertThat(failure.getRetryAfterSeconds()).hasValue(7));
        verify(limiter).check(key("ip:unknown", "tiered"), 2, 30_000);
    }

    @Test
    void classLimitAppliesUnlessMethodOverridesIt() {
        when(limiter.check(key("ip:unknown", "classLimited"), 3, 60_000)).thenReturn(RateLimitDecision.permit());
        when(limiter.check(key("ip:unknown", "override"), 1, 60_000)).thenReturn(RateLimitDecision.permit());
        service.classLimited();
        service.override();
        verify(limiter).check(key("ip:unknown", "classLimited"), 3, 60_000);
        verify(limiter).check(key("ip:unknown", "override"), 1, 60_000);
    }

    private String key(String caller, String method) {
        String handler = LimitedService.class.getName() + "#" + method + "()";
        return "autolimit:v1:7:default8:endpoint" + handler.length() + ":" + handler
                + caller.length() + ":" + caller;
    }
    private void resolveCaller(String userId, String... roles) {
        when(identityResolver.resolve()).thenReturn(Optional.of(new CallerIdentity(userId, List.of(roles))));
    }

    @Test
    void resolverFailuresPropagateWithoutConsumingAnonymousQuota() {
        IllegalStateException failure = new IllegalStateException("identity unavailable");
        when(identityResolver.resolve()).thenThrow(failure);
        assertThatThrownBy(service::tiered).isSameAs(failure);
        verifyNoInteractions(limiter);
    }

    @Test
    void nullResolverResultFailsWithoutConsumingAnonymousQuota() {
        when(identityResolver.resolve()).thenReturn(null);
        assertThatThrownBy(service::tiered).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(limiter);
    }

    @RateLimit(fallbackRequests = 3)
    public static class LimitedService {
        @RateLimit(value = {@UserQuota(role = "PREMIUM", maxRequests = 10),
                @UserQuota(role = "ENTERPRISE", maxRequests = 20)},
                fallbackRequests = 2, window = 30, unit = TimeUnit.SECONDS)
        public String tiered() { return "ok"; }
        public void classLimited() {}
        @RateLimit(window = Long.MAX_VALUE, unit = TimeUnit.DAYS)
        public void invalidWindow() { throw new AssertionError("Invalid endpoint was invoked"); }
        @RateLimit(fallbackRequests = 1) public void override() {}
    }
}
