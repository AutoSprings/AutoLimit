# AutoAuth + AutoLimit consuming application

This independent Gradle build is the only application code that imports both
libraries. Composite builds use source checkouts, so no release or local Maven
publication is required. Check out AutoAuth's `split/authentication-only` and
AutoLimit's `split/independent-library` branches in sibling directories.

The example excludes AutoAuth's transitive Swagger UI starter. Its existing
2.8.17 version pulls Boot 3.5 components into the library's Boot 3.2.4 baseline,
which breaks full application startup with mixed Spring Data versions. The
example uses the existing 3.2.4 baseline and leaves both libraries' dependency
versions unchanged. Swagger compatibility remains a separate AutoAuth concern.

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

Tests generate ephemeral RSA keys and JWTs. They verify separate user quotas,
highest-role quotas, anonymous limiting, authentication, exactly one IP filter
invocation per HTTP request, servlet registration outside the security chain,
and early IP rejection before JWT validation and endpoint quota consumption.
