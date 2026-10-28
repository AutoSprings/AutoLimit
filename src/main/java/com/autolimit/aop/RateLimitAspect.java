package com.autolimit.aop;

import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.exception.RateLimitExceededException;
import com.autolimit.ratelimit.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Aspect that intercepts methods or classes annotated with {@link RateLimit}.
 * Resolves the calling user or IP address and delegates rate checking to {@link RateLimitService}.
 */
@Aspect
public class RateLimitAspect {

    private final RateLimitService rateLimitService;
    private final CallerIdentityResolver identityResolver;

    public RateLimitAspect(RateLimitService rateLimitService,
                           CallerIdentityResolver identityResolver) {
        this.rateLimitService = rateLimitService;
        this.identityResolver = identityResolver;
    }

    /**
     * If the Endpoint has {@code @RateLimit} annotation we check the request to see if the user
     * is within the allowed limits.
     *
     * @param joinPoint metadata of the intercepted method
     */
    @Before("@within(com.autolimit.annotation.RateLimit) || @annotation(com.autolimit.annotation.RateLimit)")
    public void checkRateLimit(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // fetch the spring bean instance
        Class<?> targetClass = joinPoint.getTarget().getClass();

        RateLimit annotation = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, RateLimit.class);
        }

        if (annotation != null) {
            // pass context of user and method to rate limiter
            enforceLimit(annotation, method.getName());
        }
    }

    /**
     * enforces the rate limit using rate limit service (caffeine in memory default, redis interface planned)
     *
     * @param rateLimit the limits laid out in the annotation of the method
     * @param methodName the full method name {@code @RateLimit} is on
     *
     * @see RateLimitService
     */
    private void enforceLimit(RateLimit rateLimit, String methodName) {
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
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isBlank()) {
                    ip = request.getRemoteAddr();
                } else {
                    ip = ip.split(",")[0].trim();
                }
                callerKey = "ip:" + ip;
            } else {
                callerKey = "ip:unknown";
            }
        }

        String cacheKey = callerKey + ":" + methodName;
        long windowMillis = rateLimit.unit().toMillis(rateLimit.window());

        boolean allowed = rateLimitService.isAllowed(cacheKey, maxRequests, windowMillis);

        // throw and return a 429 rate limit exceeded error
        if (!allowed) {
            throw new RateLimitExceededException("Rate Limit Exceeded. Please try again later.");
        }
    }
}
