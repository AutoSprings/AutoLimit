package com.autolimit.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IpRateLimitFilterTest {
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
    void usesFirstForwardedIpAcrossDifferentProxies() throws Exception {
        IpRateLimitFilter filter = new IpRateLimitFilter(1);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("10.0.0.1", "203.0.113.1, 10.0.0.1"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("10.0.0.2", "203.0.113.1, 10.0.0.2"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    private MockHttpServletRequest request(String remote, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
