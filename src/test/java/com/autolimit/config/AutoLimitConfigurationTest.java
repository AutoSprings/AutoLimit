package com.autolimit.config;

import com.autolimit.aop.RateLimitAspect;
import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.exception.AutoLimitRateLimitExceptionResolver;
import com.autolimit.filter.IpRateLimitFilter;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutoLimitConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AutoLimitAutoConfiguration.class))
            .withUserConfiguration(WebConfig.class);

    @Test
    void startsWithoutAuthKeysOrSecurityAndBindsDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RateLimitService.class)
                    .hasSingleBean(RateLimitAspect.class).hasSingleBean(CallerIdentityResolver.class)
                    .hasSingleBean(AutoLimitRateLimitExceptionResolver.class).hasSingleBean(IpRateLimitFilter.class);
            assertThat(context.getBean(CallerIdentityResolver.class).resolve()).isEmpty();
            assertThat(context.getBean(AutoLimitProperties.class).getPreAuthRateLimit()).isEqualTo(50);
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
                    .andExpect(jsonPath("$.error").value("Too Many Requests"));
            mvc.perform(get("/limited").with(request -> { request.setRemoteAddr("203.0.113.2"); return request; }))
                    .andExpect(status().isOk());
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
        RateLimitService limiter = new RateLimitService();
        CallerIdentityResolver resolver = Optional::empty;
        RateLimitAspect aspect = new RateLimitAspect(limiter, resolver);
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

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(Routes.class)
    static class WebConfig {}

    @RestController
    public static class Routes {
        @RateLimit(value = {@UserQuota(role = "PREMIUM", maxRequests = 2),
                @UserQuota(role = "ENTERPRISE", maxRequests = 3)}, fallbackRequests = 1)
        @GetMapping("/limited") public String limited() { return "limited"; }
    }
}
