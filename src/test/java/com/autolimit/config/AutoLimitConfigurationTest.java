package com.autolimit.config;

import com.autolimit.aop.RateLimitAspect;
import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.exception.AutoLimitRateLimitExceptionResolver;
import com.autolimit.filter.IpRateLimitFilter;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;
import com.autolimit.identity.TrustedProxyClientIpResolver;
import com.autolimit.identity.ClientIpResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.autolimit.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutoLimitConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AutoLimitAutoConfiguration.class))
            .withUserConfiguration(WebConfig.class);

    @Test
    void interfaceAnnotationsAreEnforcedThroughBootDiscoveryWithoutProxyingUnrelatedBeans() {
        runner.withBean(InterfaceRoutes.class, InterfaceRoutes::new)
                .withBean(UnrelatedFinalBean.class, UnrelatedFinalBean::new).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(
                            context.getBean(UnrelatedFinalBean.class))).isFalse();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/interface-limit")).andExpect(status().isOk());
                    mvc.perform(get("/interface-limit")).andExpect(status().isTooManyRequests());
                });
    }

    public static final class UnrelatedFinalBean {}
    @Test
    void rejectsJdkControllerProxiesThatWouldSilentlyLoseMappings() {
        runner.withBean(HiddenController.class, HiddenController::new).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.aop.proxy-target-class=true");
        });
    }
    public interface HiddenApi { @RateLimit @GetMapping("/hidden") String hidden(); }
    @RestController
    public static class HiddenController implements HiddenApi {
        public String hidden() { return "ok"; }
    }

    @RestController
    public interface LimitedApi {
        @RateLimit(fallbackRequests = 1)
        @GetMapping("/interface-limit") String limited();
    }
    @RestController
    public static class InterfaceRoutes implements LimitedApi {
        public String limited() { return "ok"; }
    }

    @Test
    void startsWithoutAuthKeysOrSecurityAndBindsDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RateLimitService.class)
                    .hasSingleBean(RateLimitAspect.class).hasSingleBean(CallerIdentityResolver.class)
                    .hasSingleBean(AutoLimitRateLimitExceptionResolver.class).hasSingleBean(IpRateLimitFilter.class);
            assertThat(context.getBean(CallerIdentityResolver.class).resolve()).isEmpty();
            assertThat(context.getBean(AutoLimitProperties.class).getPreAuthRateLimit()).isEqualTo(50);
            assertThat(context.getBean(AutoLimitProperties.class).getMaxTrackedKeys()).isEqualTo(100_000);
            assertThat(context.getBean(AutoLimitProperties.class).isIpLimitEnabled()).isTrue();
            assertThatThrownBy(() -> Class.forName("org.springframework.security.core.Authentication"))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(() -> Class.forName("com.autoauth.config.AutoAuthAutoConfiguration"))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(() -> Class.forName("io.jsonwebtoken.Jwts"))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(() -> Class.forName("io.swagger.v3.oas.models.OpenAPI"))
                    .isInstanceOf(ClassNotFoundException.class);
        });
    }

    @Test
    void anonymousQuotaUsesIpAndReturnsJson429() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/limited").with(request -> { request.setRemoteAddr("203.0.113.1"); return request; }))
                    .andExpect(status().isOk());
            mvc.perform(get("/limited").with(request -> { request.setRemoteAddr("203.0.113.1"); return request; }))
                    .andExpect(status().isTooManyRequests()).andExpect(content().contentType("application/json"))
                    .andExpect(result -> assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After")))
                            .isBetween(1L, 60L))
                    .andExpect(jsonPath("$.error").value("Too Many Requests"));
            mvc.perform(get("/limited").with(request -> { request.setRemoteAddr("203.0.113.2"); return request; }))
                    .andExpect(status().isOk());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forgedHeadersCannotResetAnonymousEndpointQuota(boolean trustedProxy) {
        runner.withPropertyValues("autolimit.trusted-proxies[0]=10.0.0.0/24").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String peer = trustedProxy ? "10.0.0.2" : "203.0.113.20";
            String suffix = trustedProxy ? ",203.0.113.20,10.0.0.1" : "";
            mvc.perform(get("/limited").header("X-Forwarded-For", "198.51.100.1" + suffix)
                    .with(request -> { request.setRemoteAddr(peer); return request; }))
                    .andExpect(status().isOk());
            mvc.perform(get("/limited").header("X-Forwarded-For", "198.51.100.2" + suffix)
                    .with(request -> { request.setRemoteAddr(peer); return request; }))
                    .andExpect(status().isTooManyRequests());
        });
    }

    @Test
    void rejectionBeforeTheHandlerDoesNotChargeEndpointQuota() {
        var limiter = spy(new RateLimitService());
        runner.withBean(RateLimitService.class, () -> limiter).run(context -> {
            jakarta.servlet.Filter upstreamAuthorization = (request, response, chain) -> {
                if (((jakarta.servlet.http.HttpServletRequest) request).getHeader("Deny") != null) {
                    ((jakarta.servlet.http.HttpServletResponse) response).setStatus(403);
                } else {
                    chain.doFilter(request, response);
                }
            };
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(upstreamAuthorization).build();
            mvc.perform(get("/limited").header("Deny", "true")).andExpect(status().isForbidden());
            verifyNoInteractions(limiter);
            mvc.perform(get("/limited")).andExpect(status().isOk());
            mvc.perform(get("/limited")).andExpect(status().isTooManyRequests());
        });
    }

    @Test
    void handlerAuthorizationFailuresConsumeQuotaWithoutRefund() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/forbidden")).andExpect(status().isForbidden());
            mvc.perform(get("/forbidden")).andExpect(status().isTooManyRequests());
            mvc.perform(get("/unlimited")).andExpect(status().isOk());
            mvc.perform(get("/unlimited")).andExpect(status().isOk());
        });
    }

    @Test
    void zeroQuotaAndCapacityRejectionsOmitUnknownRetryTime() {
        runner.withPropertyValues("autolimit.max-tracked-keys=1").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/zero")).andExpect(status().isTooManyRequests())
                    .andExpect(header().doesNotExist("Retry-After"));
            mvc.perform(get("/limited")).andExpect(status().isOk());
            mvc.perform(get("/limited").with(request -> {
                request.setRemoteAddr("203.0.113.99"); return request;
            })).andExpect(status().isTooManyRequests()).andExpect(header().doesNotExist("Retry-After"));
        });
    }

    @Test
    void unannotatedAndUnmappedUrlsStillConsumeTheEarlyIpQuota() {
        runner.withPropertyValues("autolimit.pre-auth-rate-limit=1").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(IpRateLimitFilter.class)).build();
            mvc.perform(get("/unlimited").with(request -> {
                request.setRemoteAddr("203.0.113.10"); return request;
            })).andExpect(status().isOk());
            mvc.perform(get("/missing")).andExpect(status().isNotFound());
            mvc.perform(get("/unlimited")).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"));
        });
    }

    @Test
    void customBackendDecisionsControlBothQuotaLayersAndTheBooleanWrapper() {
        var backend = new RateLimitService() {
            @Override
            public com.autolimit.ratelimit.RateLimitDecision check(String key, int quota, long window) {
                return com.autolimit.ratelimit.RateLimitDecision.deny(7);
            }
        };
        assertThat(backend.isAllowed("direct", 1, 1000)).isFalse();
        runner.withBean(RateLimitService.class, () -> backend).run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/limited")).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "7"));
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            context.getBean(IpRateLimitFilter.class).doFilter(
                    new org.springframework.mock.web.MockHttpServletRequest(), response,
                    (request, result) -> { throw new AssertionError("Rejected before chain"); });
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"autolimit.trusted-proxies[0]=localhost", "autolimit.trusted-proxies[0]=10.0.0.1/33",
            "autolimit.max-forwarded-header-chars=0", "autolimit.max-forwarded-header-chars=-1",
            "autolimit.max-forwarded-hops=0", "autolimit.max-forwarded-hops=-1"})
    void invalidProxyConfigurationFailsStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsProxyConfigurationAndRespectsCustomClientIpResolver() {
        runner.withPropertyValues("autolimit.trusted-proxies[0]=10.0.0.0/24",
                "autolimit.max-forwarded-header-chars=100", "autolimit.max-forwarded-hops=3").run(context -> {
            var properties = context.getBean(AutoLimitProperties.class);
            assertThat(properties.getTrustedProxies()).containsExactly("10.0.0.0/24");
            assertThat(properties.getMaxForwardedHeaderChars()).isEqualTo(100);
            assertThat(properties.getMaxForwardedHops()).isEqualTo(3);
            assertThat(context).hasSingleBean(ClientIpResolver.class);
        });
        ClientIpResolver custom = request -> "203.0.113.20";
        runner.withBean(ClientIpResolver.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(ClientIpResolver.class);
            assertThat(context.getBean(ClientIpResolver.class)).isSameAs(custom);
        });
    }

    @Test
    void applicationResolverUsesActualUserIdsAndHighestMatchingRoleQuota() {
        AtomicReference<CallerIdentity> caller = new AtomicReference<>(new CallerIdentity("alice", List.of("premium")));
        CallerIdentityResolver resolver = () -> Optional.of(caller.get());
        runner.withBean(CallerIdentityResolver.class, () -> resolver).run(context -> {
            assertThat(context).hasSingleBean(CallerIdentityResolver.class);
            assertThat(context.getBean(CallerIdentityResolver.class)).isSameAs(resolver);
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (int i = 0; i < 2; i++) mvc.perform(get("/limited")).andExpect(status().isOk());
            mvc.perform(get("/limited")).andExpect(status().isTooManyRequests());
            caller.set(new CallerIdentity("bob", List.of("PREMIUM", "ENTERPRISE")));
            for (int i = 0; i < 3; i++) mvc.perform(get("/limited")).andExpect(status().isOk());
            mvc.perform(get("/limited")).andExpect(status().isTooManyRequests());
            caller.set(new CallerIdentity("carol", List.of("USER")));
            mvc.perform(get("/limited")).andExpect(status().isOk());
            mvc.perform(get("/limited")).andExpect(status().isTooManyRequests());
        });
    }

    @Test
    void respectsApplicationProvidedServiceAspectResolverHandlerAndFilter() {
        RateLimitService limiter = new RateLimitService(3);
        CallerIdentityResolver resolver = Optional::empty;
        RateLimitAspect aspect = new RateLimitAspect(limiter, resolver,
                new TrustedProxyClientIpResolver(List.of(), 8192, 32));
        AutoLimitRateLimitExceptionResolver handler = new AutoLimitRateLimitExceptionResolver();
        IpRateLimitFilter filter = new IpRateLimitFilter(4);
        runner.withBean(RateLimitService.class, () -> limiter).withBean(CallerIdentityResolver.class, () -> resolver)
                .withBean(RateLimitAspect.class, () -> aspect)
                .withBean(AutoLimitRateLimitExceptionResolver.class, () -> handler)
                .withBean(IpRateLimitFilter.class, () -> filter).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RateLimitService.class)
                            .hasSingleBean(RateLimitAspect.class).hasSingleBean(CallerIdentityResolver.class)
                            .hasSingleBean(AutoLimitRateLimitExceptionResolver.class).hasSingleBean(IpRateLimitFilter.class);
                    assertThat(context.getBean(RateLimitService.class)).isSameAs(limiter);
                    assertThat(context.getBean(RateLimitAspect.class)).isSameAs(aspect);
                    assertThat(context.getBean(CallerIdentityResolver.class)).isSameAs(resolver);
                    assertThat(context.getBean(AutoLimitRateLimitExceptionResolver.class)).isSameAs(handler);
                    assertThat(context.getBean(IpRateLimitFilter.class)).isSameAs(filter);
                    assertThat(context.getBean(FilterRegistrationBean.class).getFilter()).isSameAs(filter);
                });
    }

    @Test
    void respectsApplicationProvidedFilterRegistration() {
        IpRateLimitFilter filter = new IpRateLimitFilter(4);
        FilterRegistrationBean<IpRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        runner.withBean(IpRateLimitFilter.class, () -> filter)
                .withBean("autoLimitIpFilterRegistration", FilterRegistrationBean.class, () -> registration)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FilterRegistrationBean.class);
                    assertThat(context.getBean(FilterRegistrationBean.class)).isSameAs(registration);
                });
    }

    @Test
    void bindsIpConfigurationAndCanDisableOnlyTheIpFilter() {
        runner.withPropertyValues("autolimit.pre-auth-rate-limit=7", "autolimit.ip-filter-order=-120").run(context -> {
            assertThat(context.getBean(AutoLimitProperties.class).getPreAuthRateLimit()).isEqualTo(7);
            assertThat(context.getBean(FilterRegistrationBean.class).getOrder()).isEqualTo(-120);
        });
        runner.withPropertyValues("autolimit.ip-limit-enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(IpRateLimitFilter.class)
                        .doesNotHaveBean(FilterRegistrationBean.class).hasSingleBean(RateLimitAspect.class));
        runner.withPropertyValues("autolimit.pre-auth-rate-limit=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuredCapacityIsEnforcedByTheAutoConfiguredService() {
        runner.withPropertyValues("autolimit.max-tracked-keys=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AutoLimitProperties.class).getMaxTrackedKeys()).isEqualTo(1);
            var limiter = context.getBean(RateLimitService.class);
            assertThat(limiter.isAllowed("alice", 2, 60_000)).isTrue();
            assertThat(limiter.isAllowed("bob", 2, 60_000)).isFalse();
            assertThat(limiter.isAllowed("alice", 2, 60_000)).isTrue();
            assertThat(limiter.isAllowed("alice", 2, 60_000)).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidCapacityFailsStartup(int capacity) {
        runner.withPropertyValues("autolimit.max-tracked-keys=" + capacity)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void autoConfiguredIpFilterSharesTheConfiguredCapacityWithEndpointQuotas() {
        runner.withPropertyValues("autolimit.max-tracked-keys=1").run(context -> {
            var limiter = context.getBean(RateLimitService.class);
            assertThat(limiter.isAllowed("endpoint", 2, 60_000)).isTrue();
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.1");
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            context.getBean(IpRateLimitFilter.class).doFilter(request, response,
                    (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Capacity must deny"); });
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(limiter.isAllowed("endpoint", 2, 60_000)).isTrue();
            assertThat(limiter.isAllowed("endpoint", 2, 60_000)).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(Routes.class)
    static class WebConfig {}

    @Test
    void applicationNamespacesIsolateEndpointAndIpQuotasOnSharedLimiter() {
        // A nonexpiring one-request backend keeps this wiring test independent of
        // the IP filter's one-second window and context startup time.
        var sharedLimiter = mock(RateLimitService.class);
        var consumedKeys = new java.util.HashSet<String>();
        when(sharedLimiter.check(anyString(), anyInt(), anyLong()))
                .thenAnswer(call -> consumedKeys.add(call.getArgument(0))
                        ? com.autolimit.ratelimit.RateLimitDecision.permit()
                        : com.autolimit.ratelimit.RateLimitDecision.deny());
        var namespaces = List.of("orders", "billing", "orders");
        for (int index = 0; index < namespaces.size(); index++) {
            String namespace = namespaces.get(index);
            boolean exhausted = index == 2;
            runner.withBean(RateLimitService.class, () -> sharedLimiter)
                    .withPropertyValues("autolimit.namespace=" + namespace, "autolimit.pre-auth-rate-limit=1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AutoLimitProperties.class).getNamespace()).isEqualTo(namespace);
                        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
                        mvc.perform(get("/limited")).andExpect(status().is(exhausted ? 429 : 200));
                        mvc.perform(get("/limited")).andExpect(status().isTooManyRequests());
                        var response = new org.springframework.mock.web.MockHttpServletResponse();
                        context.getBean(IpRateLimitFilter.class).doFilter(
                                new org.springframework.mock.web.MockHttpServletRequest(), response,
                                (request, result) -> {});
                        assertThat(response.getStatus()).isEqualTo(exhausted ? 429 : 200);
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankNamespaceFailsStartup(String namespace) {
        runner.withPropertyValues("autolimit.namespace=" + namespace)
                .run(context -> assertThat(context).hasFailed());
    }

    @RestController
    public static class Routes {
        @RateLimit(fallbackRequests = 0)
        @GetMapping("/zero") public String zero() { throw new AssertionError("Must not be invoked"); }

        @RateLimit(fallbackRequests = 1)
        @GetMapping("/forbidden") public String forbidden() {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }

        @GetMapping("/unlimited") public String unlimited() { return "ok"; }

        @RateLimit(value = {@UserQuota(role = "PREMIUM", maxRequests = 2),
                @UserQuota(role = "ENTERPRISE", maxRequests = 3)}, fallbackRequests = 1)
        @GetMapping("/limited") public String limited() { return "limited"; }
    }
}
