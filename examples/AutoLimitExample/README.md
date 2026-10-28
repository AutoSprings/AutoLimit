# AutoLimitExample

A blank Java 21 Gradle project for writing your own AutoLimit example.

Open this directory as a Gradle project in your IDE. Add your application code
under `src/main/java`, configuration under `src/main/resources`, and tests under
`src/test/java`. No application code or rate limiting configuration is provided.

AutoLimit is already a dependency and supplies Spring Boot's web starter.
`settings.gradle` includes the local AutoLimit build, so changes to the library
are picked up without publishing to Maven Local. Spring Boot's test starter is
also available.

Build from the repository root using the existing Gradle wrapper:

```powershell
.\gradlew.bat -p examples/AutoLimitExample build
```

Once you add a Spring Boot application entry point, run its `main` method from
your IDE.
