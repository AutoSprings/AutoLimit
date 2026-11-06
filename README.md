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
is needed. The library uses the Spring Boot 3.5.16 dependency baseline, with the
embedded Tomcat modules constrained to at least 10.1.61. Caffeine is managed by
the Boot BOM. Consuming applications should inspect their resolved dependency
graph when supplying their own platform or version overrides.

Boot 3.5.16 is the final public Boot 3 release and is no longer under open-source
support. This baseline updates the previously flagged Spring/Tomcat versions
while retaining Boot 3 compatibility; it does not promise ongoing security
maintenance. A supported Boot 4 migration or commercial Boot 3 support is still
needed for continued upstream fixes. See the
[Boot release notice](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).

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
Interface method annotations, inherited class defaults, overridden methods, and
methods inherited by an annotated subclass are resolved using Spring's annotation
hierarchy search. Method annotations take precedence over class defaults.
`RateLimitAspect` retains its bean name/type and constructors but now implements a
Spring advisor with an annotation-aware method matcher. Manually assembled proxy
factories must register it with `addAdvisor(...)` rather than `addAspect(...)`.
Controller interfaces used with JDK proxies must also carry `@Controller` or
`@RestController`; alternatively use class proxies (Boot's default). AutoLimit
rejects a quota-advised JDK controller proxy at startup if its controller
stereotype is hidden, with an actionable error instead of silently losing routes.

Without an identity resolver, all callers use IP quotas. With a resolver,
authenticated callers use `user:<userId>` and anonymous callers use `ip:<clientIp>`.
Each endpoint quota combines that caller identity with the application namespace,
concrete target class, method name, and parameter types. Unrelated controllers,
overloads, and concrete subclasses therefore have independent quotas, including
when a method is inherited. JDK and class proxies of the same implementation use
the same handler identity; generated proxy class names are excluded. Class-level
annotations still create a separate quota per method. Multiple URL mappings for
the same method share its quota. The highest matching role quota wins, ignoring case;
users without matching roles use `fallbackRequests` under their own user ID.

Exhausted annotation quotas return JSON with HTTP 429, `error`, and `message`.
When a quota window is exhausted, the response includes `Retry-After` with the
remaining window duration rounded up to whole seconds. The independent IP filter
also returns JSON 429, with `Retry-After: 1` for its exhausted one-second window.
Zero quotas and storage-capacity rejections omit the header because there is no
reliable retry time. See the request behavior below for charging and exclusions.

## Configuration

```yaml
autolimit:
  namespace: catalog # stable application identity; default is "default"
  pre-auth-rate-limit: 50 # requests per IP per one-second window; must be positive
  ip-limit-enabled: true # false disables only the early IP filter
  ip-filter-order: -110  # before Boot's default Spring Security order of -100
  max-tracked-keys: 100000 # positive capacity shared by IP-filter and endpoint keys
```

Choose a distinct `autolimit.namespace` for each application that shares a limiter.
The namespace applies to endpoint and early IP quotas and must be nonblank.
Keep it stable across instances of the same application; do not use a per-instance
random value. The default is suitable for the default per-process limiter.
Namespaces and caller IDs are length-prefixed in internal keys so delimiters in
their values cannot merge fields. Internal key strings are opaque and have changed
from the previous caller-plus-method-name format; custom backends must account for
the new keys. Changing a namespace or renaming/moving a handler starts fresh quotas.

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
When AutoLimit owns registration, `ip-limit-enabled=false` also disables an
application-provided filter bean by installing a disabled registration, preventing
Boot's automatic fallback registration. An application-owned registration remains
the application's responsibility, including whether it is enabled.

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

## Request behavior and exclusions

The early IP filter applies to all URLs and HTTP methods on initial `REQUEST`
dispatches, including public, login, health, static-resource, and unmapped URLs.
There are no built-in path exclusions. It does not run again on asynchronous or
error dispatches. Disabling `autolimit.ip-limit-enabled` disables only this filter;
changing its URL coverage requires an application-owned filter registration or
custom filter. An unannotated endpoint is still subject to the early IP filter.

The IP filter charges one unit before calling the remaining servlet chain,
including authentication and URL authorization at the default filter order.
Later 401, 403, 404, or 5xx responses do not refund that unit. Invalid trusted
proxy headers rejected during IP resolution do not consume an IP quota. If the
IP quota rejects a request, the downstream chain and endpoint quota are skipped.

Endpoint quotas charge immediately before the annotated method executes. A
request rejected by upstream authentication, URL authorization, an interceptor,
or argument binding before that invocation does not consume endpoint quota.
Once admitted by the aspect, a request stays charged even if the method throws,
returns an error, or rejects ownership/authorization inside its body. AutoLimit
does not establish ordering relative to other method-level AOP advice, including
Spring method security; the application's advisor order determines which check
runs first. Configure and test that order in the consuming application.

Only intercepted calls to annotated methods or classes receive endpoint quotas.
Calls on objects constructed outside Spring, direct calls to the target, and
self-invocation bypass the aspect. Private/static methods and final methods on
class proxies cannot be intercepted. Annotations are not URL exclusions for the
IP filter. Resolver failures propagate before endpoint quota consumption, but
any earlier IP charge remains. Quota rejections themselves do not increase the
counter, and no success-only charging or rollback is provided.

## Fixed windows, bursts, and retry timing

Counters use atomic per-key fixed windows, starting on the first admitted request.
Active counters survive idle periods until their window boundary. Accepted and
rejected attempts do not extend that boundary. A window is anchored to the first
admitted request for each key, not a wall-clock minute. After expiry, the next
admitted request starts a new window; idle windows do not accumulate credits.

This is a fixed-window admission limit, not a rolling-window rate, token bucket,
leaky bucket, request pacing mechanism, or cap on simultaneous in-flight work.
For example, with five requests per minute, admit one at t=0, four just before
t=60 seconds, then five at t=60 seconds. Nine requests can pass close together
across the boundary. For a constant quota N, the first-request anchor permits up
to 2N-1 requests around that boundary, approaching twice the quota for large N.
This burst behavior is intentional. Concurrent requests for the same key are
serialized atomically: only the remaining quota is admitted, and an expired
window is renewed once. Different keys have independent counters.

The admission decision captures retry timing during that same atomic update.
For a rejected active window, 1.001 seconds remaining yields `Retry-After: 2`;
any positive subsecond remainder yields `Retry-After: 1`. This uses the HTTP
[delay-seconds format](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after).
The delay is measured at rejection and is a retry hint, not a reservation or a
promise of admission: another request may consume quota first. Zero quotas,
capacity exhaustion, custom decisions with unknown timing, and exceptions
created with the legacy message-only constructor omit `Retry-After`.

Windows must convert exactly to
whole milliseconds from 1 ms through 365 days. Zero, negative, fractional
millisecond, oversized, and overflowing annotation durations throw
`IllegalArgumentException` on invocation before consuming quota. Zero request
quotas always deny without allocating state; negative quotas are invalid.

The selected role/fallback request threshold is evaluated on every invocation.
Changing that threshold changes the allowance against the existing count, while
an active window keeps its original deadline. Use consistent window settings
for a key; a new duration takes effect when a new window starts.

## Storage and backend API

`autolimit.max-tracked-keys` defaults to 100,000 and must be positive. It bounds
the number of distinct tracked quota keys across both the IP filter and endpoint
quotas. At capacity, expired keys are reclaimed; if all slots are active, new keys
are denied (HTTP 429) while existing keys retain their counters and allowances.
Active quotas are never evicted to admit another identity. Long windows can
therefore keep new identities blocked until a slot expires. A bounded deadline
index reclaims expired slots without scanning all active keys on every rejection.
Reclamation stops once a slot is free. Renewed windows can leave stale earlier
deadlines; these are checked atomically and refreshed without evicting active
quotas. The index retains at most one record per tracked key. Reclamation can
process multiple expired or stale records, so choose capacity and windows for
your traffic; saturation with only future deadlines requires constant work.
This is a key-count bound, not a byte limit; key lengths also affect memory use.

For direct Java usage, `new RateLimitService()` uses the default capacity and
`new RateLimitService(10_000)` sets it explicitly. `isAllowed(key, quota,
windowMillis)` returns false for both quota and capacity exhaustion. Use
consistent window settings for each key; an active window keeps its original
deadline until expiry.

`check(key, quota, windowMillis)` consumes at most one unit and returns a
`RateLimitDecision` with `allowed()` and optional `retryAfterSeconds()`. Do not
call both `check` and `isAllowed` for the same request: each consumes quota.
`isAllowed` is a boolean wrapper around `check`. The aspect and IP filter now use
`check`; custom `RateLimitService` subclasses/backends must override `check`
rather than only `isAllowed`. Return `RateLimitDecision.permit()`, `deny()`, or
`deny(positiveSeconds)`; the backend must compute the decision and retry timing
atomically. No distributed adapter is supplied or certified by this library.

Manually constructed aspects and filters retain their existing constructors,
which use namespace `default`. To share a limiter between applications, use
`new RateLimitAspect(limiter, identityResolver, clientIpResolver, "catalog")` and
`new IpRateLimitFilter(50, clientIpResolver, limiter, "catalog")`. Direct callers
of `RateLimitService.isAllowed` remain responsible for their own unique keys.
Separate bean instances with the same concrete class and method share endpoint
quotas within a namespace; bean-name isolation and explicit shared policy IDs are
not supported.

Counters are local to each `RateLimitService` instance, reset on restart, and are
not shared between processes. Boot normally supplies one service per application
context. A shared service's storage capacity applies across namespaces, but each
namespaced quota has its own counter. Multiple replicas each have their own
allowance; adding replicas can increase aggregate admissions, without creating a
global quota. Independently constructed services have independent allowances.
Redis and token/leaky buckets are not implemented. Application namespaces separate
keys but do not provide distributed counters. Proxy headers are used only for configured
`autolimit.trusted-proxies`; by default the socket peer supplies the client IP.
With Spring's `server.forward-headers-strategy=framework`, AutoLimit unwraps
servlet request wrappers and uses the original peer and X-Forwarded-For chain,
so Spring's rewritten address cannot bypass AutoLimit's proxy trust rules.
Container-native forwarding rewrites the request before servlet filters; use
`server.forward-headers-strategy=none` or `framework` with the default resolver.
Native forwarding requires an application-owned `ClientIpResolver` and a verified
container/proxy trust configuration; AutoLimit cannot restore that original peer.
AutoLimit contains no revocation store. Token revocation belongs to the
authentication library and must retain active revocations until token expiry;
this quota cache and its admission policy are not a revocation mechanism.

## Attribution

The annotations, limiter, aspect, IP filter, identity interfaces, exception
resolver, and original limiter tests were copied from
[AutoSprings/AutoAuth](https://github.com/AutoSprings/AutoAuth), using
[`separate-auth-limit` at d388ffd](https://github.com/AutoSprings/AutoAuth/tree/d388ffd135fec8ab6307bdef9d9bcee067ee43fc)
as the extraction source, plus local resolver-focused test preparation. Original
source history remains in AutoAuth. The original project's README identifies
its license as MIT.
