package com.autolimit.ratelimit;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Builds unambiguous, application-scoped keys for the library's quota policies. */
public final class QuotaKeyFactory {
    public static final String DEFAULT_NAMESPACE = "default";
    private final String namespace;

    public QuotaKeyFactory(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        this.namespace = namespace;
    }

    public String endpoint(Class<?> targetClass, Method method, String caller) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getName).collect(Collectors.joining(","));
        String handler = targetClass.getName() + "#" + method.getName() + "(" + parameters + ")";
        return encode(namespace, "endpoint", handler, caller);
    }

    public String preAuthIp(String ip) {
        return encode(namespace, "preauth", "ip:" + ip);
    }

    // Length prefixes prevent delimiters in namespaces or caller IDs from joining fields.
    private static String encode(String... parts) {
        StringBuilder key = new StringBuilder("autolimit:v1:");
        for (String part : parts) {
            key.append(part.length()).append(':').append(part);
        }
        return key.toString();
    }
}
