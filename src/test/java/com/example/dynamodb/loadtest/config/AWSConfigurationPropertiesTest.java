package com.example.dynamodb.loadtest.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AWSConfigurationProperties.
 */
class AWSConfigurationPropertiesTest {

    @Test
    void defaultValues() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();

        // Then
        assertThat(properties.getRegion()).isEqualTo("us-east-1");
        assertThat(properties.getEndpointUrl()).isNull();
        assertThat(properties.isLocalEnvironment()).isFalse();
    }

    @Test
    void setRegion() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();

        // When
        properties.setRegion("us-west-2");

        // Then
        assertThat(properties.getRegion()).isEqualTo("us-west-2");
    }

    @Test
    void setEndpointUrl() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();

        // When
        properties.setEndpointUrl("http://localhost:4566");

        // Then
        assertThat(properties.getEndpointUrl()).isEqualTo("http://localhost:4566");
    }

    @Test
    void isLocalEnvironment_WithLocalhost() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();
        properties.setEndpointUrl("http://localhost:4566");

        // Then
        assertThat(properties.isLocalEnvironment()).isTrue();
    }

    @Test
    void isLocalEnvironment_With127001() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();
        properties.setEndpointUrl("http://127.0.0.1:4566");

        // Then
        assertThat(properties.isLocalEnvironment()).isTrue();
    }

    @Test
    void isLocalEnvironment_WithAWSEndpoint() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();
        properties.setEndpointUrl("https://dynamodb.us-east-1.amazonaws.com");

        // Then
        assertThat(properties.isLocalEnvironment()).isFalse();
    }

    @Test
    void isLocalEnvironment_WithNullEndpoint() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();
        properties.setEndpointUrl(null);

        // Then
        assertThat(properties.isLocalEnvironment()).isFalse();
    }
}