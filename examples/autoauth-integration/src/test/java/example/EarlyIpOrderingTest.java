package example;

import com.autoauth.jwt.JwtGenerator;
import com.autoauth.jwt.JwtValidator;
import com.autoauth.model.AutoAuthUser;
import com.autolimit.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = IntegrationApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "autolimit.pre-auth-rate-limit=1")
class EarlyIpOrderingTest {
    @Autowired TestRestTemplate http;
    @Autowired JwtGenerator tokens;
    @SpyBean JwtValidator validator;
    @SpyBean RateLimitService limiter;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry properties) throws Exception { IntegrationTest.keys(properties); }

    @Test
    void earlyIpRejectionSkipsJwtValidationAndEndpointQuota() {
        // Warm the HTTP/MVC infrastructure using another IP before the one-second burst.
        http.getForEntity("/private", String.class);
        clearInvocations(validator, limiter);
        String token = tokens.generateAccessToken(new AutoAuthUser("early", List.of("PREMIUM"), null));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-For", "203.0.113.30");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        assertThat(http.exchange("/limited", HttpMethod.GET, request, String.class).getBody()).isEqualTo("early");
        ResponseEntity<String> rejected = http.exchange("/limited", HttpMethod.GET, request, String.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(429);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(rejected.getBody()).contains("IP rate limit exceeded");
        verify(validator, times(1)).validateAccessToken(token);
        verify(limiter, times(1)).isAllowed("user:early:limited", 3, 60_000);
    }
}
