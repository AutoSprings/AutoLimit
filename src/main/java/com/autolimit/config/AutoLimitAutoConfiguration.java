package com.autolimit.config;

import com.autolimit.aop.RateLimitAspect;
import com.autolimit.exception.AutoLimitRateLimitExceptionResolver;
import com.autolimit.filter.IpRateLimitFilter;
import com.autolimit.identity.CallerIdentityResolver;
import com.autolimit.identity.ClientIpResolver;
import com.autolimit.identity.TrustedProxyClientIpResolver;
import com.autolimit.ratelimit.RateLimitService;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Optional;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AutoLimitProperties.class)
@EnableAspectJAutoProxy
public class AutoLimitAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RateLimitService rateLimitService(AutoLimitProperties properties){
        return new RateLimitService(properties.getMaxTrackedKeys());
    }

    @Bean
    @ConditionalOnMissingBean(CallerIdentityResolver.class)
    public CallerIdentityResolver callerIdentityResolver() { return Optional::empty; }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitAspect rateLimitAspect(RateLimitService limiter, CallerIdentityResolver resolver,
                                         ClientIpResolver clientIpResolver, AutoLimitProperties properties) {
        return new RateLimitAspect(limiter, resolver, clientIpResolver, properties.getNamespace());
    }

    @Bean
    @ConditionalOnMissingBean
    public AutoLimitRateLimitExceptionResolver autoLimitRateLimitExceptionResolver() {
        return new AutoLimitRateLimitExceptionResolver();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "autolimit", name = "ip-limit-enabled", matchIfMissing = true)
    static class IpFilterConfiguration {
        @Bean
        @ConditionalOnMissingBean
        IpRateLimitFilter ipRateLimitFilter(AutoLimitProperties properties, ClientIpResolver clientIpResolver,
                                           RateLimitService rateLimitService) {
            return new IpRateLimitFilter(properties.getPreAuthRateLimit(), clientIpResolver,
                    rateLimitService, properties.getNamespace());
        }

    }

    @Bean
    @ConditionalOnBean(IpRateLimitFilter.class)
    @ConditionalOnMissingBean(name = "autoLimitIpFilterRegistration")
    FilterRegistrationBean<IpRateLimitFilter> autoLimitIpFilterRegistration(
            IpRateLimitFilter filter, AutoLimitProperties properties) {
        FilterRegistrationBean<IpRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("autoLimitIpRateLimitFilter");
        registration.setEnabled(properties.isIpLimitEnabled());
        registration.setOrder(properties.getIpFilterOrder());
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(ClientIpResolver.class)
    public ClientIpResolver clientIpResolver(AutoLimitProperties properties){
        return new TrustedProxyClientIpResolver(
                properties.getTrustedProxies(),
                properties.getMaxForwardedHeaderChars(),
                properties.getMaxForwardedHops()
        );
    }
}
