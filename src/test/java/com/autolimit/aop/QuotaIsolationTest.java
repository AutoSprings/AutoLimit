package com.autolimit.aop;

import com.autolimit.annotation.RateLimit;
import com.autolimit.exception.RateLimitExceededException;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class QuotaIsolationTest {
    private final RateLimitService limiter = new RateLimitService();

    @Test
    void directCallsAndSelfInvocationBypassAnnotationQuota() {
        First target = new First();
        target.list();
        target.list();
        First proxied = proxy(target, "app", true, "alice");
        proxied.selfCall();
        proxied.list();
        assertThatThrownBy(proxied::list).isInstanceOf(RateLimitExceededException.class);
    }

    private <T> T proxy(T target, String namespace, boolean classProxy, String user) {
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(classProxy);
        factory.addAspect(new RateLimitAspect(limiter,
                () -> user == null ? Optional.empty() : Optional.of(new CallerIdentity(user, List.of())),
                request -> "203.0.113.1", namespace));
        return factory.getProxy();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedControllersAndOverloadsHaveIndependentQuotas(boolean authenticated) {
        String user = authenticated ? "alice" : null;
        First first = proxy(new First(), "app", true, user);
        Second second = proxy(new Second(), "app", true, user);
        first.list();
        assertThatThrownBy(first::list).isInstanceOf(RateLimitExceededException.class);
        second.list();
        first.list("query");
        assertThatThrownBy(second::list).isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> first.list("query")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void namespacesIsolateSameHandlerAndCallerEvenWithDelimiters() {
        First first = proxy(new First(), "app:one", true, "alice:two");
        First second = proxy(new First(), "app", true, "alice:two");
        first.list();
        second.list();
        assertThatThrownBy(first::list).isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(second::list).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void inheritedMethodsAreIsolatedByConcreteTargetClass() {
        ChildOne first = proxy(new ChildOne(), "app", true, "alice");
        ChildTwo second = proxy(new ChildTwo(), "app", true, "alice");
        first.list();
        second.list();
        assertThatThrownBy(first::list).isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(second::list).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void jdkAndClassProxiesUseSameBridgeResolvedImplementationPolicy() {
        GenericApi<String> jdk = proxy(new GenericHandler(), "app", false, "alice");
        GenericHandler cglib = proxy(new GenericHandler(), "app", true, "alice");
        jdk.list("query");
        assertThatThrownBy(() -> cglib.list("query")).isInstanceOf(RateLimitExceededException.class);
        GenericApi<String> other = proxy(new OtherGenericHandler(), "app", false, "alice");
        other.list("query");
        assertThatThrownBy(() -> other.list("query")).isInstanceOf(RateLimitExceededException.class);
    }

    @RateLimit(fallbackRequests = 1)
    public static class First {
        public void list() {}
        public void list(String query) {}
        public void selfCall() { list(); }
    }

    @RateLimit(fallbackRequests = 1)
    public static class Second { public void list() {} }

    public static class ChildOne extends First {}
    public static class ChildTwo extends First {}

    public interface GenericApi<T> { void list(T query); }

    public static class GenericHandler implements GenericApi<String> {
        @RateLimit(fallbackRequests = 1)
        public void list(String query) {}
    }

    @RateLimit(fallbackRequests = 1)
    public static class OtherGenericHandler implements GenericApi<String> {
        public void list(String query) {}
    }
}
