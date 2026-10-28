package com.autolimit.aop;

import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.exception.RateLimitExceededException;
import com.autolimit.ratelimit.RateLimitService;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;
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
        AspectJProxyFactory factory = new AspectJProxyFactory(new LimitedService());
        factory.addAspect(new RateLimitAspect(limiter, identityResolver));
        service = factory.getProxy();
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void choosesHighestMatchingRoleQuotaCaseInsensitively() {
        resolveCaller("alice", "premium", "ENTERPRISE");
        when(limiter.isAllowed("user:alice:tiered", 20, 30_000)).thenReturn(true);
        assertThat(service.tiered()).isEqualTo("ok");
        verify(limiter).isAllowed("user:alice:tiered", 20, 30_000);
    }

    @Test
    void unmatchedRolesUseFallbackQuota() {
        resolveCaller("alice", "USER");
        when(limiter.isAllowed("user:alice:tiered", 2, 30_000)).thenReturn(true);
        service.tiered();
        verify(limiter).isAllowed("user:alice:tiered", 2, 30_000);
    }

    @Test
    void callerWithoutRolesUsesUserKeyAndFallbackQuota() {
        resolveCaller("bob");
        when(limiter.isAllowed("user:bob:tiered", 2, 30_000)).thenReturn(true);
        service.tiered();
        verify(limiter).isAllowed("user:bob:tiered", 2, 30_000);
    }

    @Test
    void resolvesCallerForEachInvocation() {
        when(identityResolver.resolve())
                .thenReturn(Optional.of(new CallerIdentity("alice", List.of("PREMIUM"))))
                .thenReturn(Optional.of(new CallerIdentity("bob", List.of("ENTERPRISE"))));
        when(limiter.isAllowed("user:alice:tiered", 10, 30_000)).thenReturn(true);
        when(limiter.isAllowed("user:bob:tiered", 20, 30_000)).thenReturn(true);
        service.tiered();
        service.tiered();
        verify(limiter).isAllowed("user:alice:tiered", 10, 30_000);
        verify(limiter).isAllowed("user:bob:tiered", 20, 30_000);
        verify(identityResolver, times(2)).resolve();
    }

    @Test
    void anonymousRequestsUseFirstForwardedIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.isAllowed("ip:203.0.113.10:tiered", 2, 30_000)).thenReturn(true);
        service.tiered();
        verify(limiter).isAllowed("ip:203.0.113.10:tiered", 2, 30_000);
    }

    @Test
    void anonymousRequestsFallBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.11");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.isAllowed("ip:203.0.113.11:tiered", 2, 30_000)).thenReturn(true);
        service.tiered();
        verify(limiter).isAllowed("ip:203.0.113.11:tiered", 2, 30_000);
    }

    @Test
    void exceededQuotaPreventsMethodInvocation() {
        assertThatThrownBy(service::tiered).isInstanceOf(RateLimitExceededException.class);
        verify(limiter).isAllowed("ip:unknown:tiered", 2, 30_000);
    }

    @Test
    void classLimitAppliesUnlessMethodOverridesIt() {
        when(limiter.isAllowed("ip:unknown:classLimited", 3, 60_000)).thenReturn(true);
        when(limiter.isAllowed("ip:unknown:override", 1, 60_000)).thenReturn(true);
        service.classLimited();
        service.override();
        verify(limiter).isAllowed("ip:unknown:classLimited", 3, 60_000);
        verify(limiter).isAllowed("ip:unknown:override", 1, 60_000);
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
        @RateLimit(fallbackRequests = 1) public void override() {}
    }
}
