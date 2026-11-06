package com.autolimit.config;

import com.autolimit.filter.IpRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;

@AutoConfigureTestRestTemplate
@SpringBootTest(classes=DisabledFilterOverrideTest.App.class,
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"autolimit.ip-limit-enabled=false"})
class DisabledFilterOverrideTest {
    @Autowired TestRestTemplate http;
    @SpringBootConfiguration @EnableAutoConfiguration @Import(Routes.class)
    static class App { @Bean IpRateLimitFilter overrideFilter() { return new IpRateLimitFilter(1); } }
    @RestController public static class Routes {
        @GetMapping("/probe") public String probe() { return "ok"; }
    }
    @Test void disablingTheFilterAlsoDisablesTheSupportedBeanOverride() {
        assertThat(http.getForEntity("/probe",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/probe",String.class).getStatusCode().value()).isEqualTo(200);
    }
}
