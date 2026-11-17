package com.jobradar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jobradar")
public record JobRadarProperties(Admin admin, Jwt jwt, Cors cors, Scan scan, Mail mail) {
    public record Admin(String email, String password) {}
    public record Jwt(String secret, int ttlHours) {}
    public record Cors(String allowedOrigins) {}
    public record Scan(String userAgent, int timeoutSeconds, int retries, long backoffMillis,
                       int maxJobPagesPerCompany, boolean playwrightEnabled, boolean allowPrivateHosts,
                       String cron, String zone, boolean scheduleEnabled) {}
    public record Mail(boolean enabled, String from, String to) {}
}
