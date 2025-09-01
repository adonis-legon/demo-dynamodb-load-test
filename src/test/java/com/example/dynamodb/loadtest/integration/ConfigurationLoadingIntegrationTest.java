package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.ssm.model.DeleteParameterRequest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for configuration loading from SSM Parameter Store.
 */
class ConfigurationLoadingIntegrationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private ConfigurationManager configurationManager;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldLoadAllRequiredParametersFromSSM() {
        // When
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then
        assertThat(config).isNotNull();
        assertThat(config.getTableName()).isEqualTo(TEST_TABLE_NAME);
        assertThat(config.getConcurrencyLimit()).isEqualTo(5);
        assertThat(config.getTotalItems()).isEqualTo(50);
        assertThat(config.getMaxConcurrencyPercentage()).isEqualTo(80.0);
        assertThat(config.getDuplicatePercentage()).isGreaterThan(0.0);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleMissingParameterGracefully() {
        // Given - Delete a required parameter
        String parameterName = SSM_PARAMETER_PREFIX + "/table-name";
        try {
            ssmClient.deleteParameter(DeleteParameterRequest.builder()
                    .name(parameterName)
                    .build()).join();
        } catch (Exception e) {
            // Parameter might not exist, ignore
        }

        // When & Then
        assertThatThrownBy(() -> configurationManager.loadConfiguration().join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldValidateParameterValues() {
        // Given - Set invalid concurrency limit
        putSSMParameter("concurrency-limit", "0");

        // When & Then
        assertThatThrownBy(() -> configurationManager.loadConfiguration().join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        // Restore valid value
        putSSMParameter("concurrency-limit", "5");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldValidatePercentageParameters() {
        // Given - Set invalid percentage
        putSSMParameter("max-concurrency-percentage", "150.0");

        // When & Then
        assertThatThrownBy(() -> configurationManager.loadConfiguration().join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        // Restore valid value
        putSSMParameter("max-concurrency-percentage", "80.0");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleDoubleParameters() {
        // Given - Test different double values
        putSSMParameter("duplicate-percentage", "0.0");

        // When
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then
        assertThat(config.getDuplicatePercentage()).isEqualTo(0.0);

        // Test with positive percentage
        putSSMParameter("duplicate-percentage", "25.5");
        config = configurationManager.loadConfiguration().join();
        assertThat(config.getDuplicatePercentage()).isEqualTo(25.5);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldDetectEnvironmentCorrectly() {
        // When
        boolean isLocal = configurationManager.isLocalEnvironment();
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then
        assertThat(isLocal).isTrue();
        assertThat(config.getEnvironment()).isNotNull();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleParameterCaching() {
        // When - Load configuration multiple times
        TestConfiguration config1 = configurationManager.loadConfiguration().join();
        TestConfiguration config2 = configurationManager.loadConfiguration().join();

        // Then - Should return consistent results
        assertThat(config1.getTableName()).isEqualTo(config2.getTableName());
        assertThat(config1.getConcurrencyLimit()).isEqualTo(config2.getConcurrencyLimit());
        assertThat(config1.getTotalItems()).isEqualTo(config2.getTotalItems());
        assertThat(config1.getMaxConcurrencyPercentage()).isEqualTo(config2.getMaxConcurrencyPercentage());
        assertThat(config1.getDuplicatePercentage()).isEqualTo(config2.getDuplicatePercentage());
    }
}