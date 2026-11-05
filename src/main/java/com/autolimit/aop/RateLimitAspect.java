package com.autolimit.aop;

import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.exception.RateLimitExceededException;
import com.autolimit.identity.ClientIpResolver;
import com.autolimit.ratelimit.RateLimitService;
import com.autolimit.ratelimit.QuotaKeyFactory;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * Aspect that intercepts methods or classes annotated with {@link RateLimit}.
 * Resolves the calling user or IP address and delegates rate checking to {@link RateLimitService}.
 */
public class RateLimitAspect extends StaticMethodMatcherPointcutAdvisor {

    private final RateLimitService rateLimitService;
    private final CallerIdentityResolver identityResolver;
    private final ClientIpResolver clientIpResolver;
    private final QuotaKeyFactory quotaKeys;

    public RateLimitAspect(RateLimitService rateLimitService,
                           CallerIdentityResolver identityResolver,
                           ClientIpResolver clientIpResolver) {
        this(rateLimitService, identityResolver, clientIpResolver, QuotaKeyFactory.DEFAULT_NAMESPACE);
    }

    /** Creates endpoint quotas isolated within the supplied application namespace. */
    public RateLimitAspect(RateLimitService rateLimitService,
                           CallerIdentityResolver identityResolver,
                           ClientIpResolver clientIpResolver, String namespace) {
        this.rateLimitService = rateLimitService;
        this.identityResolver = identityResolver;
        this.clientIpResolver = clientIpResolver;
        this.quotaKeys = new QuotaKeyFactory(namespace);
        setAdvice((MethodInterceptor) invocation -> {
            Class<?> targetClass = ClassUtils.getUserClass(invocation.getThis());
            checkMethod(invocation.getMethod(), targetClass);
            return invocation.proceed();
        });
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return findRateLimit(method, ClassUtils.getUserClass(targetClass)) != null;
    }

    /**
     * If the Endpoint has {@code @RateLimit} annotation we check the request to see if the user
     * is within the allowed limits.
     *
     * @param joinPoint metadata of the intercepted method
     */
    public void checkRateLimit(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> targetClass = ClassUtils.getUserClass(joinPoint.getTarget());
        checkMethod(signature.getMethod(), targetClass);
    }

    private static RateLimit findRateLimit(Method method, Class<?> targetClass) {
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        RateLimit annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, RateLimit.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        }
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, RateLimit.class);
        }

        return annotation;
    }

    private void checkMethod(Method method, Class<?> targetClass) {
        RateLimit annotation = findRateLimit(method, targetClass);
        if (annotation != null) {
            // pass context of user and method to rate limiter
            enforceLimit(annotation, targetClass, AopUtils.getMostSpecificMethod(method, targetClass));
        }
    }

    /**
     * enforces the rate limit using rate limit service (caffeine in memory default, redis interface planned)
     *
     * @param rateLimit the limits laid out in the annotation of the method
     * @param targetClass the concrete handler class, excluding generated proxy subclasses
     * @param method the most specific, bridge-resolved handler method
     *
     * @see RateLimitService
     */
    private void enforceLimit(RateLimit rateLimit, Class<?> targetClass, Method method) {
        String callerKey;
        Optional<CallerIdentity> caller = identityResolver.resolve();

        int maxRequests = rateLimit.fallbackRequests();

        // limit based on user id and role with the highest limit
        if (caller.isPresent()) {
            CallerIdentity identity = caller.get();
            callerKey = "user:" + identity.userId();

            OptionalInt highestLimit = Arrays.stream(rateLimit.value())
                    .filter(quota -> identity.roles().stream()
                            .anyMatch(role -> role.equalsIgnoreCase(quota.role())))
                    .mapToInt(UserQuota::maxRequests)
                    .max();

            if (highestLimit.isPresent()) {
                maxRequests = highestLimit.getAsInt();
            }

        // limit based on IP if no user is found
        } else {
            var attributes = RequestContextHolder.getRequestAttributes();

            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                String ip = clientIpResolver.resolve(
                        servletAttributes.getRequest());

                callerKey = "ip:" + ip;
            } else {
                callerKey = "ip:unknown";
            }
        }

        String cacheKey = quotaKeys.endpoint(targetClass, method, callerKey);
        long windowMillis = windowMillis(rateLimit.window(), rateLimit.unit());

        var decision = rateLimitService.check(cacheKey, maxRequests, windowMillis);

        // throw and return a 429 rate limit exceeded error
        if (!decision.allowed()) {
            throw new RateLimitExceededException("Rate Limit Exceeded. Please try again later.",
                    decision.retryAfterSeconds());
        }
    }

    static long windowMillis(long window, TimeUnit unit) {
        long millis = unit.toMillis(window);
        // TimeUnit saturates on overflow and truncates sub-millisecond values.
        // Reject both instead of silently changing the configured quota window.
        if (window <= 0 || millis < 1 || millis > TimeUnit.DAYS.toMillis(365)
                || unit.convert(millis, TimeUnit.MILLISECONDS) != window) {
            throw new IllegalArgumentException(
                    "RateLimit window must be an exact number of milliseconds between 1 ms and 365 days");
        }
        return millis;
    }
}
