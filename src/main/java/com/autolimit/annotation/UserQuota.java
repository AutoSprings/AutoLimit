package com.autolimit.annotation;

import java.lang.annotation.*;

/**
 * Enforces rate limiting based on a user role
 * <p>
 *     Set allowed number of requests each role can have. Rate limiter tracks
 *     limits by the uuid ({@code user:userId}).
 * </p>
 *
 * <h2>SaaS Quotas</h2>
 * By nesting {@code @UserQuota} inside of {@link RateLimit} you can assign
 * specific requests thresholds based on user roles. If a user holds multiple
 * roles, the limit automatically goes to the highest limit based on their roles.
 *
 * <h2>Usage Examples:</h2>
 * <pre>{@code
 * @RateLimit(
 *      value = {
 *           @UserQuota(role = "USER", maxRequests = 3),
 *           @UserQuota(role = "PREMIUM", maxRequests = 10)
 *      },
 *      fallbackRequests = 3,
 *      window = 3,
 *      unit = TimeUnit.MINUTES
 * )
 * @GetMapping("limited-data")
 * Public Data getLimitedData() {...}
 * }</pre>
 *
 * @see RateLimit
 * @see com.autolimit.aop.RateLimitAspect
 * @since 1.0.0
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UserQuota {

    String role();
    int maxRequests();

}
