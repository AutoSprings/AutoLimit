package example;

import com.autoauth.annotation.PublicEndpoint;
import com.autoauth.util.AuthContext;
import com.autolimit.annotation.RateLimit;
import com.autolimit.annotation.UserQuota;
import com.autolimit.identity.CallerIdentity;
import com.autolimit.identity.CallerIdentityResolver;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class IntegrationApplication {
    public static void main(String[] args) { SpringApplication.run(IntegrationApplication.class, args); }

    // Application-owned bridge: neither library imports or depends on the other.
    @Bean
    CallerIdentityResolver authenticatedCaller() {
        return () -> AuthContext.getCurrentUser()
                .map(user -> new CallerIdentity(user.userId(), user.roles()));
    }

    @RestController
    public static class Routes {
        @PublicEndpoint
        @RateLimit(value = {@UserQuota(role = "PREMIUM", maxRequests = 3),
                @UserQuota(role = "ENTERPRISE", maxRequests = 4)}, fallbackRequests = 2)
        @GetMapping("/limited")
        public String limited() { return AuthContext.getCurrentUserId().orElse("anonymous"); }

        @GetMapping("/private")
        public String secured() { return AuthContext.getCurrentUserId().orElseThrow(); }
    }
}
