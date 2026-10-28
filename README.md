# AutoLimit

Configure rate limiting on endpoints with annotations for Java and Spring.
AutoLimit is an independent Java 21 / Spring Boot 3 servlet library. It supplies
in-memory endpoint quotas and an early IP filter. It needs no AutoAuth, Spring
Security, JWT keys, JJWT, Swagger, or Redis.

## Install

This branch uses `com.autolimit:autolimit:1.0.0-SNAPSHOT`; it does not publish a
release. Build and install locally first:

```powershell
.\gradlew.bat build publishToMavenLocal
```

Then add it to a Java 21 Spring Boot application:

```groovy
repositories { mavenCentral(); mavenLocal() }
dependencies { implementation 'com.autolimit:autolimit:1.0.0-SNAPSHOT' }
```

Spring Boot discovers `AutoLimitAutoConfiguration` from its auto-configuration
imports metadata. No enable annotation, component scan, or authentication setup
is needed. The library uses the same Spring Boot 3.2.4 dependency baseline as its
source project.

## Standalone usage

```java
import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
    @RateLimit(fallbackRequests = 5)
    @GetMapping("/search")
    public String search() { return "results"; }

    @RateLimit(value = @UserQuota(role = "PREMIUM", maxRequests = 100),
               fallbackRequests = 10)
    @GetMapping("/data")
    public String data() { return "data"; }
}
```

The default annotation window is one minute; customize `window` and `unit`
(`java.util.concurrent.TimeUnit`). Class annotations apply to all intercepted
methods; method annotations override class defaults. Like other Spring AOP
features, interception requires calls through the Spring proxy.

Without an identity resolver, all callers use IP quotas. With a resolver,
authenticated callers use `user:<userId>:<methodName>` and anonymous callers use
`ip:<clientIp>:<methodName>`. The highest matching role quota wins, ignoring case;
users without matching roles use `fallbackRequests` under their own user ID.

Exhausted annotation quotas return JSON with HTTP 429, `error`, and `message`.
The independent IP filter also returns JSON 429 and `Retry-After: 1`.

## Configuration

```yaml
autolimit:
  pre-auth-rate-limit: 50 # requests per IP per one-second window; must be positive
  ip-limit-enabled: true # false disables only the early IP filter
  ip-filter-order: -110  # before Boot's default Spring Security order of -100
```

The IP filter applies to all request URLs, including authenticated requests,
before endpoint quotas. AutoLimit registers its single `IpRateLimitFilter` bean
through a `FilterRegistrationBean`, for `REQUEST` dispatches only. Do not add it
to a Spring Security filter chain as well. If security's servlet order is
customized, set `ip-filter-order` to a smaller number.

Applications may override `RateLimitService`, `RateLimitAspect`,
`CallerIdentityResolver`, `AutoLimitRateLimitExceptionResolver`, or
`IpRateLimitFilter` by supplying a bean of that type. To own servlet registration,
provide a `FilterRegistrationBean<IpRateLimitFilter>` named
`autoLimitIpFilterRegistration` referencing the application's filter bean.

## Authenticated identity

Implement `com.autolimit.identity.CallerIdentityResolver` as a Spring bean:

```java
@Bean
CallerIdentityResolver callerIdentityResolver() {
    return () -> currentUserProvider.currentUser()
            .map(user -> new CallerIdentity(user.id(), user.roles()));
}
```

Here `currentUserProvider` represents your application's authenticated user
provider. Return `Optional.empty()` only for an anonymous caller. Return the
actual authenticated user ID and roles; never invent a shared authenticated ID.
Provider failures propagate instead of silently using anonymous quotas. The
resolver must return a non-null `Optional`, and identities require a nonblank
user ID and a non-null list of roles.

For AutoAuth, the application bridge is:

```java
import com.autoauth.util.AuthContext;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;
import org.springframework.context.annotation.Bean;

@Bean
CallerIdentityResolver callerIdentityResolver() {
    return () -> AuthContext.getCurrentUser()
            .map(user -> new CallerIdentity(user.userId(), user.roles()));
}
```

Code importing both libraries lives in the consuming application. Neither base
library depends on the other. See the runnable
[AutoAuth integration example](examples/autoauth-integration/README.md).

## Build and verify

Use Java 21 and the included Gradle wrapper (`./gradlew` on macOS/Linux):

```powershell
.\gradlew.bat build
.\gradlew.bat dependencies --configuration runtimeClasspath
.\gradlew.bat dependencies --configuration testRuntimeClasspath
```

The standalone suite checks startup via Boot discovery without authentication
libraries, anonymous and user/role quotas, resolver failures, application bean
overrides, 429 responses, and single servlet filter registration. The separate
example tests the two libraries together with real JWTs and HTTP requests.

## Migration from bundled AutoAuth limiting

Add AutoLimit explicitly, change limiter imports from `com.autoauth` to
`com.autolimit`, and rename `AutoAuthRateLimitExceptionResolver` to
`AutoLimitRateLimitExceptionResolver`. Move `autoauth.pre-auth-rate-limit` to
`autolimit.pre-auth-rate-limit`. Applications using both libraries must provide
the identity bridge above to enable authenticated quotas. AutoAuth alone no
longer installs any limiter.

## Current limits

This extraction retains the original in-memory fixed-window implementation.
Counters are per process, not shared between instances; Redis and token/leaky
buckets are not implemented. Existing concerns include non-atomic endpoint
counters under concurrency, method-name key collisions across controllers, and
15-minute idle eviction for longer annotation windows. Both IP paths currently
trust the first `X-Forwarded-For` value; a trusted proxy must sanitize that header.
These behaviors were not redesigned as part of the repository split.

## Attribution

The annotations, limiter, aspect, IP filter, identity interfaces, exception
resolver, and original limiter tests were copied from
[AutoSprings/AutoAuth](https://github.com/AutoSprings/AutoAuth), using
[`separate-auth-limit` at d388ffd](https://github.com/AutoSprings/AutoAuth/tree/d388ffd135fec8ab6307bdef9d9bcee067ee43fc)
as the extraction source, plus local resolver-focused test preparation. Original
source history remains in AutoAuth. The original project's README identifies
its license as MIT.
