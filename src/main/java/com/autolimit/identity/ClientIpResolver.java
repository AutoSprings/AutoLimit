package com.autolimit.identity;

import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface ClientIpResolver {

    // Return a client IP Address suitable for a rate limit
    String resolve(HttpServletRequest request);

}
