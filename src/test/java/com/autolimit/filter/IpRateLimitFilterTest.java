package com.autolimit.filter;

import jakarta.servlet.FilterChain;
import com.autolimit.identity.TrustedProxyClientIpResolver;
import com.autolimit.ratelimit.RateLimitService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IpRateLimitFilterTest {
    @Test
    void capacityRejectsNewIpsWithoutForgettingActiveQuotas() throws Exception {
        var limiter = new RateLimitService(1);
        var filter = new IpRateLimitFilter(2, request -> request.getRemoteAddr(), limiter);
        var chain = mock(FilterChain.class);
        filter.doFilter(request("203.0.113.1", null), new MockHttpServletResponse(), chain);
        clearInvocations(chain);
        var newIp = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.2", null), newIp, chain);
        assertThat(newIp.getStatus()).isEqualTo(429);
        assertThat(newIp.getHeader("Retry-After")).isNull();
        verifyNoInteractions(chain);
        filter.doFilter(request("203.0.113.1", null), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        clearInvocations(chain);
        var exhausted = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.1", null), exhausted, chain);
        assertThat(exhausted.getStatus()).isEqualTo(429);
        assertThat(limiter.isAllowed("ip:203.0.113.1:endpoint", 1, 60_000)).isFalse();
        verifyNoInteractions(chain);
    }

    @Test
    void allowsRequestsUpToQuotaThenReturns429() throws Exception {
        IpRateLimitFilter filter = new IpRateLimitFilter(1);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest first = request("203.0.113.1", null);
        filter.doFilter(first, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(eq(first), any());
        clearInvocations(chain);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.1", null), rejected, chain);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentType()).isEqualTo("application/json");
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejected.getContentAsString()).contains("Too Many Requests");
        verifyNoInteractions(chain);
        filter.doFilter(request("203.0.113.2", null), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void usesResolvedClientAcrossExplicitlyTrustedProxies() throws Exception {
        IpRateLimitFilter filter = new IpRateLimitFilter(1,
                new TrustedProxyClientIpResolver(List.of("10.0.0.0/24"), 8192, 32));
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("10.0.0.1", "203.0.113.1, 10.0.0.1"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("10.0.0.2", "203.0.113.1, 10.0.0.2"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingForgedHeadersCannotResetQuota(boolean trustedProxy) throws Exception {
        var filter = new IpRateLimitFilter(1,
                new TrustedProxyClientIpResolver(List.of("10.0.0.0/24"), 8192, 32));
        var chain = mock(FilterChain.class);
        String peer = trustedProxy ? "10.0.0.2" : "203.0.113.20";
        String suffix = trustedProxy ? ",203.0.113.20,10.0.0.1" : "";
        var accepted = new MockHttpServletResponse();
        filter.doFilter(request(peer, "198.51.100.1" + suffix), accepted, chain);
        assertThat(accepted.getStatus()).isEqualTo(200);
        clearInvocations(chain);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request(peer, "198.51.100.2" + suffix), rejected, chain);
        assertThat(rejected.getStatus()).isEqualTo(429);
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "", "203.0.113.1,", "1.1.1.1,2.2.2.2", "203.0.113.12345"})
    void invalidTrustedHeadersReturn400WithoutConsumingQuota(String header) throws Exception {
        var filter = new IpRateLimitFilter(1,
                new TrustedProxyClientIpResolver(List.of("10.0.0.0/24"), 13, 1));
        var chain = mock(FilterChain.class);
        var response = new MockHttpServletResponse();
        filter.doFilter(request("10.0.0.2", header), response, chain);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).isEqualTo("Invalid Client IP");
        verifyNoInteractions(chain);
        var valid = request("10.0.0.2", "203.0.113.1");
        var accepted = new MockHttpServletResponse();
        filter.doFilter(valid, accepted, chain);
        assertThat(accepted.getStatus()).isEqualTo(200);
        verify(chain).doFilter(valid, accepted);
    }

    @Test
    void equivalentIpv6AddressesShareQuota() throws Exception {
        var filter = new IpRateLimitFilter(1);
        var chain = mock(FilterChain.class);
        filter.doFilter(request("2001:0DB8:0:0:0:0:0:1", null), new MockHttpServletResponse(), chain);
        clearInvocations(chain);
        var response = new MockHttpServletResponse();
        filter.doFilter(request("2001:db8::1", null), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        verifyNoInteractions(chain);
    }

    private MockHttpServletRequest request(String remote, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
