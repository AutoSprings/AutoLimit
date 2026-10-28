package com.autolimit.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceptionResolverTest {
    @Test
    void handlesQuotaFailureWithJson429() throws Exception {
        var response = new MockHttpServletResponse();
        var result = new AutoLimitRateLimitExceptionResolver().resolveException(
                new MockHttpServletRequest(), response, null, new RateLimitExceededException("exceeded"));
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json");
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("message").asText()).isNotBlank();
    }

    @Test
    void leavesOtherFailuresForOtherResolvers() {
        var response = new MockHttpServletResponse();
        assertThat(new AutoLimitRateLimitExceptionResolver().resolveException(
                new MockHttpServletRequest(), response, null, new IllegalStateException("other"))).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
}
