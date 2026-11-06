# AutoAuth + AutoLimit consuming application

This independent Gradle build is the only application code that imports both
libraries. Composite builds use source checkouts, so no release or local Maven
publication is required. Check out AutoAuth's `split/authentication-only` and
AutoLimit's `split/independent-library` branches in sibling directories.

The example excludes AutoAuth's optional transitive Swagger UI starter and uses
the same Boot 3.5.16 BOM and minimum Tomcat 10.1.61 constraints as AutoLimit. The
AutoAuth checkout is unchanged; the consuming application's BOM aligns its older
transitive Spring dependencies. Swagger compatibility is not tested here.
Boot 3.5 is out of open-source support; continued upstream security maintenance
requires a supported Boot migration or commercial support.

From the AutoLimit root with Java 21:

```powershell
.\gradlew.bat -p examples/autoauth-integration build
.\gradlew.bat -p examples/autoauth-integration dependencies --configuration runtimeClasspath
```

For another layout, add `-PautoauthDir=C:/path/to/AutoAuth` (or an absolute Unix
path). The standalone AutoLimit build never includes this example or AutoAuth.

To run the application, supply your own PEM keys through Spring configuration
(`autoauth.private-key`, `autoauth.public-key`), then run:

```powershell
.\gradlew.bat -p examples/autoauth-integration run
```

`GET /limited` allows anonymous callers (two requests per minute per IP).
Provide a valid AutoAuth access token using `Authorization: Bearer <token>` to
use the token's actual user ID and roles: PREMIUM gets three requests per minute,
ENTERPRISE gets four, and other authenticated users get two per user. `GET
/private` requires authentication. The application supplies the resolver bridge
using `AuthContext`; the two libraries remain independent.

Set `autolimit.max-tracked-keys` (default 100,000; must be positive) to bound the
combined IP-filter and endpoint quota keys. When all slots have active windows,
new keys receive HTTP 429 until capacity expires; active quotas are retained.
Annotation windows must convert exactly to whole milliseconds between 1 ms and
365 days. Authentication token revocation is managed separately by AutoAuth.

Set `autolimit.namespace=autoauth-integration` for an explicit application identity.
Use distinct namespaces for applications sharing a limiter. Endpoint quotas use
the concrete handler class and full parameter signature, so unrelated handlers
with the same method name remain independent. The namespace also scopes early IP
quotas; counters remain per process with the default backend.

Tests generate ephemeral RSA keys and JWTs. They verify separate user quotas,
highest-role quotas, anonymous limiting, authentication, exactly one IP filter
invocation per HTTP request, servlet registration outside the security chain,
and early IP rejection before JWT validation and endpoint quota consumption.

Both quota layers use fixed windows anchored to their first admitted request;
later requests do not move the boundary. Requests can burst across a boundary.
The early IP filter charges before authentication and URL authorization, including
requests that later receive 401/403. Endpoint quotas charge only when the proxied
handler is reached and are not refunded for errors or authorization failures
inside the handler. Ordering relative to method-security advice is application
configuration; AutoLimit does not establish it.

Exhausted annotation windows return `Retry-After` rounded up to whole seconds;
exhausted one-second IP windows return `Retry-After: 1`. Storage-capacity rejection
and zero endpoint quotas omit the header because retry timing is unknown. There
are no built-in IP-filter URL exclusions, including for `/private` or login routes.
