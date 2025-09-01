package com.example.dynamodb.loadtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for DynamoDB settings.
 */
@Component
@ConfigurationProperties(prefix = "dynamodb")
public class DynamoDBConfigurationProperties {

    private String endpoint;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
}