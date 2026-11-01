package com.autolimit.identity;

import com.google.common.net.InetAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class TrustedProxyClientIpResolver implements ClientIpResolver {

    private static final String HEADER = "X-Forwarded-For";

    private final List<IpRange> trustedProxies; // needs ip range
    private final int maxHeadersChars;
    private final int maxHops;

    private final String cacheAttribute =
            getClass().getName() + "." + UUID.randomUUID();

    public TrustedProxyClientIpResolver(
            List<String> trustedProxies,
            int maxHeadersChars,
            int maxHops
    ) {

        Objects.requireNonNull(trustedProxies, "trustedProxies");

        if (maxHeadersChars < 1 || maxHops < 1) {
            throw new IllegalArgumentException(
                    "Forwarded-header limits must be positive"
            );
        }

        this.trustedProxies = trustedProxies.stream()
                .map(IpRange::parse)
                .toList();

        this.maxHeadersChars = maxHeadersChars;
        this.maxHops = maxHops;

    }

    @Override
    public String resolve(HttpServletRequest request) {

        Object cached = request.getAttribute(cacheAttribute);

        if (cached instanceof String address) {

            return address;

        }

        InetAddress peer = requestAddress(request.getRemoteAddr());

        return "";
    }

    private static InetAddress requestAddress(String value) {

        try {
            return literalAddress(value);
        } catch (IllegalArgumentException exception) {
            // Dont pass through proxy IP
            throw invalidRequest();
        }

    }

    private static InetAddress literalAddress(String value) {

        if (value == null || value.isBlank() || value.contains("%")) {

            throw new IllegalArgumentException("Expected IP literal");

        }

        // remove host name, port, brackets, format ipv6 properly
        return InetAddresses.forString(value);

    }

    private static ResponseStatusException invalidRequest() {

        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid client address information"
        );

    }
}
