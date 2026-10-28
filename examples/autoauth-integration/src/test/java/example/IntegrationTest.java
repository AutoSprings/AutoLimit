package example;

import com.autoauth.jwt.JwtGenerator;
import com.autoauth.model.AutoAuthUser;
import com.autolimit.filter.IpRateLimitFilter;
import com.autolimit.ratelimit.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {IntegrationApplication.class, IntegrationTest.Filters.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JwtGenerator tokens;
    @Autowired CountingIpFilter ipFilter;
    @Autowired ServletContext servletContext;
    @Autowired FilterChainProxy security;
    @Autowired FilterRegistrationBean<IpRateLimitFilter> registration;
    @SpyBean RateLimitService limiter;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry properties) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        properties.add("autoauth.private-key", () -> pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        properties.add("autoauth.public-key", () -> pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getEncoder().encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void authenticatedQuotasUseJwtUserIdAndRolesAndIpFilterRunsOnceBeforeAuthentication() {
        String alice = tokens.generateAccessToken(new AutoAuthUser("alice", List.of("PREMIUM"), null));
        String bob = tokens.generateAccessToken(new AutoAuthUser("bob", List.of("PREMIUM", "ENTERPRISE"), null));
        String carol = tokens.generateAccessToken(new AutoAuthUser("carol", List.of("USER"), null));
        int before = ipFilter.invocations.get();
        for (int i = 0; i < 3; i++) assertThat(request("/limited", alice, "203.0.113.10").getBody()).isEqualTo("alice");
        assertThat(request("/limited", alice, "203.0.113.10").getStatusCode().value()).isEqualTo(429);
        for (int i = 0; i < 4; i++) assertThat(request("/limited", bob, "203.0.113.10").getBody()).isEqualTo("bob");
        assertThat(request("/limited", bob, "203.0.113.10").getStatusCode().value()).isEqualTo(429);
        for (int i = 0; i < 2; i++) assertThat(request("/limited", carol, "203.0.113.10").getBody()).isEqualTo("carol");
        assertThat(request("/limited", carol, "203.0.113.10").getStatusCode().value()).isEqualTo(429);
        assertThat(ipFilter.invocations.get() - before).isEqualTo(12);
        assertThat(ipFilter.authenticatedAtEntry.get()).isZero();
        verify(limiter, times(4)).isAllowed("user:alice:limited", 3, 60_000);
        verify(limiter, times(5)).isAllowed("user:bob:limited", 4, 60_000);
        verify(limiter, times(3)).isAllowed("user:carol:limited", 2, 60_000);
        assertThat(servletContext.getFilterRegistrations().values().stream()
                .filter(filter -> filter.getClassName().equals(CountingIpFilter.class.getName()))).hasSize(1);
        assertThat(security.getFilterChains().stream().flatMap(chain -> chain.getFilters().stream())
                .filter(IpRateLimitFilter.class::isInstance)).isEmpty();
        assertThat(registration.getOrder()).isLessThan(-100);
    }

    @Test
    void anonymousQuotasAndAuthenticationStillWorkTogether() {
        assertThat(request("/limited", null, "203.0.113.20").getBody()).isEqualTo("anonymous");
        assertThat(request("/limited", null, "203.0.113.20").getStatusCode().value()).isEqualTo(200);
        assertThat(request("/limited", null, "203.0.113.20").getStatusCode().value()).isEqualTo(429);
        assertThat(request("/private", null, "203.0.113.20").getStatusCode().value()).isEqualTo(401);
        String token = tokens.generateAccessToken(new AutoAuthUser("secured", List.of("USER"), null));
        assertThat(request("/private", token, "203.0.113.20").getBody()).isEqualTo("secured");
    }

    private ResponseEntity<String> request(String path, String token, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Filters {
        @Bean CountingIpFilter countingIpFilter() { return new CountingIpFilter(); }
    }

    static class CountingIpFilter extends IpRateLimitFilter {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger authenticatedAtEntry = new AtomicInteger();
        CountingIpFilter() { super(500); }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            invocations.incrementAndGet();
            if (SecurityContextHolder.getContext().getAuthentication() != null) authenticatedAtEntry.incrementAndGet();
            super.doFilterInternal(request, response, chain);
        }
    }
}
