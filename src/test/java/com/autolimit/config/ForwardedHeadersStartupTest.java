package com.autolimit.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ForwardedHeadersStartupTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.forward-headers-strategy=framework", "autolimit.pre-auth-rate-limit=1"})
class ForwardedHeadersStartupTest {
    @Autowired TestRestTemplate http;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Routes.class)
    static class App {}

    @RestController
    public static class Routes {
        @GetMapping("/probe") public String probe() { return "ok"; }
    }

    @Test
    void changingUntrustedHeadersDoesNotResetIpQuota() {
        var first = new HttpHeaders(); first.set("X-Forwarded-For", "203.0.113.1");
        var second = new HttpHeaders(); second.set("X-Forwarded-For", "203.0.113.2");
        assertThat(http.exchange("/probe", HttpMethod.GET, new HttpEntity<>(first), String.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange("/probe", HttpMethod.GET, new HttpEntity<>(second), String.class)
                .getStatusCode().value()).isEqualTo(429);
    }
}
