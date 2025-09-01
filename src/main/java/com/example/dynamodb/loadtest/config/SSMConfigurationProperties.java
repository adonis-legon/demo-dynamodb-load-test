package com.example.dynamodb.loadtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for AWS Systems Manager (SSM) settings.
 */
@Component
@ConfigurationProperties(prefix = "ssm")
public class SSMConfigurationProperties {

    private String endpoint;
    private String parameterPrefix = "/dynamodb-load-test";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getParameterPrefix() {
        return parameterPrefix;
    }

    public void setParameterPrefix(String parameterPrefix) {
        this.parameterPrefix = parameterPrefix;
    }
}