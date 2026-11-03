package com.autolimit.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Servlet IP protection is independent of the per-endpoint annotation quotas. */
@ConfigurationProperties(prefix = "autolimit")
public class AutoLimitProperties {

    private List<String> trustedProxies = List.of();
    private int maxForwardedHeaderChars = 8192;
    private int maxForwardedHops = 32;

    private int maxTrackedKeys = 100000;

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

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = List.copyOf(trustedProxies);
    }

    public int getMaxForwardedHeaderChars() {
        return maxForwardedHeaderChars;
    }

    public void setMaxForwardedHeaderChars(int value) {
        this.maxForwardedHeaderChars = value;
    }

    public int getMaxForwardedHops() {
        return maxForwardedHops;
    }

    public void setMaxForwardedHops(int value) {
        this.maxForwardedHops = value;
    }

    public int getMaxTrackedKeys(){return maxTrackedKeys;}

    public void setMaxTrackedKeys(int maxTrackedKeys) {
        if(maxTrackedKeys < 1) {
            throw new IllegalArgumentException("max-tracked-keys must be positive");
        }
        this.maxTrackedKeys = maxTrackedKeys;
    }

}
