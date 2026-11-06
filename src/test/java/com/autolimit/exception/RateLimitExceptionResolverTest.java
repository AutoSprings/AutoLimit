package com.autolimit.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.OptionalLong;

class RateLimitExceptionResolverTest {
    @Test
    void exceptionSerializationPreservesKnownAndUnknownRetryTiming() throws Exception {
        for (OptionalLong retry : new OptionalLong[] {OptionalLong.empty(), OptionalLong.of(7)}) {
            var original = new RateLimitExceededException("exceeded", retry);
            original.initCause(new IllegalStateException("backend detail"));
            var bytes = new java.io.ByteArrayOutputStream();
            try (var output = new java.io.ObjectOutputStream(bytes)) {
                output.writeObject(original);
            }
            RateLimitExceededException restored;
            try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                restored = (RateLimitExceededException) input.readObject();
            }
            assertThat(restored.getMessage()).isEqualTo("exceeded");
            assertThat(restored.getCause()).isInstanceOf(IllegalStateException.class).hasMessage("backend detail");
            assertThat(restored.getRetryAfterSeconds()).isEqualTo(retry);
            var response = new MockHttpServletResponse();
            new AutoLimitRateLimitExceptionResolver().resolveException(
                    new MockHttpServletRequest(), response, null, restored);
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo(retry.isPresent() ? "7" : null);
        }
    }

    @Test
    void handlesQuotaFailureWithJson429() throws Exception {
        var response = new MockHttpServletResponse();
        var result = new AutoLimitRateLimitExceptionResolver().resolveException(
                new MockHttpServletRequest(), response, null, new RateLimitExceededException("exceeded"));
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Retry-After")).isNull();
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("message").asText()).isNotBlank();
    }

    @Test
    void includesKnownRetryDelayWithoutChangingTheJsonBody() throws Exception {
        var response = new MockHttpServletResponse();
        new AutoLimitRateLimitExceptionResolver().resolveException(
                new MockHttpServletRequest(), response, null,
                new RateLimitExceededException("exceeded", OptionalLong.of(3)));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("error").asText())
                .isEqualTo("Too Many Requests");
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
