package com.autolimit.config;

import com.autolimit.filter.IpRateLimitFilter;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = StandaloneStartupTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StandaloneStartupTest {
    @Autowired TestRestTemplate http;
    @Autowired ServletContext servletContext;
    @Autowired FilterRegistrationBean<IpRateLimitFilter> registration;

    @Test
    void bootDiscoversAutoConfigurationAndRegistersTheFilterOnce() {
        assertThat(http.getForEntity("/limited", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/limited", String.class).getStatusCode().value()).isEqualTo(429);
        assertThat(servletContext.getFilterRegistrations().values().stream()
                .filter(filter -> filter.getClassName().equals(IpRateLimitFilter.class.getName()))).hasSize(1);
        assertThat(registration.getOrder()).isEqualTo(-110);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AutoLimitConfigurationTest.Routes.class)
    static class Application {}
}
