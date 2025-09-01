package com.example.dynamodb.loadtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for AWS settings.
 */
@Component
@ConfigurationProperties(prefix = "aws")
public class AWSConfigurationProperties {

    private String region = "us-east-1";
    private String endpointUrl;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    /**
     * Determines if this is a local environment based on endpoint URL.
     */
    public boolean isLocalEnvironment() {
        return endpointUrl != null &&
                (endpointUrl.contains("localhost") || endpointUrl.contains("127.0.0.1"));
    }
}