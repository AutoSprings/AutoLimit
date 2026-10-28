package com.autolimit.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class IpRateLimitFilter extends OncePerRequestFilter {

    private final int maxRequestsPerWindow;
    private final Cache<String, AtomicInteger> ipRequestCounts;

    public IpRateLimitFilter(int maxRequestsPerSecond) {
        this.maxRequestsPerWindow = maxRequestsPerSecond;

        this.ipRequestCounts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1))
                .maximumSize(50_000)
                .build();
    }

    public IpRateLimitFilter() {
        this(50);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String clientIp = resolveClientIp(request);

        AtomicInteger counter = ipRequestCounts.get(clientIp, k -> new AtomicInteger(0));

        if (counter.incrementAndGet() > maxRequestsPerWindow) {
            rejectWith429(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void rejectWith429(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "1");

        response.getWriter().write("""
            {
                "status": 429,
                "error": "Too Many Requests",
                "message": "IP rate limit exceeded. Please slow down."
            }
            """);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}