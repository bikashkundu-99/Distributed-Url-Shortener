package dev.relay.shortener;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(String kafkaTopic, long cacheTtlSeconds,
                              int rateLimitPerMinute, String geoipDbPath) { }
