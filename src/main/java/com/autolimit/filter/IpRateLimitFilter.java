package com.autolimit.filter;

import com.autolimit.identity.ClientIpResolver;
import com.autolimit.identity.TrustedProxyClientIpResolver;
import com.autolimit.ratelimit.RateLimitService;
import com.autolimit.ratelimit.QuotaKeyFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public class IpRateLimitFilter extends OncePerRequestFilter {

    private final int maxRequestsPerWindow;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final QuotaKeyFactory quotaKeys;

    public IpRateLimitFilter(int maxRequestsPerSecond, ClientIpResolver clientIpResolver) {
        this(maxRequestsPerSecond, clientIpResolver, new RateLimitService());
    }

    public IpRateLimitFilter(int maxRequestsPerSecond, ClientIpResolver clientIpResolver,
                             RateLimitService rateLimitService) {
        this(maxRequestsPerSecond, clientIpResolver, rateLimitService, QuotaKeyFactory.DEFAULT_NAMESPACE);
    }

    /** Creates an early IP quota isolated within the supplied application namespace. */
    public IpRateLimitFilter(int maxRequestsPerSecond, ClientIpResolver clientIpResolver,
                             RateLimitService rateLimitService, String namespace) {
        if (maxRequestsPerSecond < 1) {
            throw new IllegalArgumentException("maxRequestsPerSecond must be positive");
        }
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver);
        this.rateLimitService = Objects.requireNonNull(rateLimitService);
        this.maxRequestsPerWindow = maxRequestsPerSecond;
        this.quotaKeys = new QuotaKeyFactory(namespace);
    }


    public IpRateLimitFilter(int maxRequestsPerSecond) {
        this (
                maxRequestsPerSecond,
                new TrustedProxyClientIpResolver(List.of(), 8192, 32)
        );
    }
    public IpRateLimitFilter() {
        this(50);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String clientIp;

        try {
            clientIp = clientIpResolver.resolve(request);
        } catch (ResponseStatusException exception) {
            response.sendError(exception.getStatusCode().value(), "Invalid Client IP");
            return;
        }

        var decision = rateLimitService.check(quotaKeys.preAuthIp(clientIp), maxRequestsPerWindow, 1_000);
        if (!decision.allowed()) {
            rejectWith429(response, decision.retryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void rejectWith429(HttpServletResponse response, OptionalLong retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        retryAfterSeconds.ifPresent(seconds -> response.setHeader("Retry-After", Long.toString(seconds)));

        response.getWriter().write("""
            {
                "status": 429,
                "error": "Too Many Requests",
                "message": "IP rate limit exceeded. Please slow down."
            }
            """);
    }

}
