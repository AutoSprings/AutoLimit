package com.autolimit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Servlet IP protection is independent of the per-endpoint annotation quotas. */
@ConfigurationProperties(prefix = "autolimit")
public class AutoLimitProperties {
    private boolean ipLimitEnabled = true;
    private int preAuthRateLimit = 50;
    // Spring Boot's default security filter order is -100.
    private int ipFilterOrder = -110;

    public boolean isIpLimitEnabled() { return ipLimitEnabled; }
    public void setIpLimitEnabled(boolean ipLimitEnabled) { this.ipLimitEnabled = ipLimitEnabled; }
    public int getPreAuthRateLimit() { return preAuthRateLimit; }
    public void setPreAuthRateLimit(int preAuthRateLimit) {
        if (preAuthRateLimit < 1) throw new IllegalArgumentException("pre-auth-rate-limit must be positive");
        this.preAuthRateLimit = preAuthRateLimit;
    }
    public int getIpFilterOrder() { return ipFilterOrder; }
    public void setIpFilterOrder(int ipFilterOrder) { this.ipFilterOrder = ipFilterOrder; }
}
