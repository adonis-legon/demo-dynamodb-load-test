package com.example.dynamodb.loadtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for application-specific settings.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppConfigurationProperties {

    private String mode = "batch";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}