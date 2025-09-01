package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the complete load test workflow.
 * Tests the full flow from configuration loading to test execution and metrics
 * collection.
 */
class LoadTestEndToEndIntegrationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private MetricsCollectionService metricsCollectionService;

    @AfterEach
    void cleanup() {
        cleanupTestData();
        metricsCollectionService.reset();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldExecuteCompleteLoadTestWorkflow() {
        // Given - Configuration is loaded from SSM
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Verify configuration was loaded correctly
        assertThat(config).isNotNull();
        assertThat(config.getTableName()).isEqualTo(TEST_TABLE_NAME);
        assertThat(config.getConcurrencyLimit()).isEqualTo(5);
        assertThat(config.getTotalItems()).isEqualTo(50);
        assertThat(config.getMaxConcurrencyPercentage()).isEqualTo(80.0);
        assertThat(config.getDuplicatePercentage()).isGreaterThan(0.0);

        // When - Execute load test
        var testSummary = loadTestService.executeLoadTest(config).join();

        // Then - Verify items were written to DynamoDB
        ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .build()).join();

        assertThat(scanResponse.items()).isNotEmpty();
        assertThat(scanResponse.count()).isLessThanOrEqualTo(config.getTotalItems());

        // Verify metrics were collected
        assertThat(testSummary).isNotNull();
        assertThat(testSummary.getTotalOperations()).isGreaterThan(0);
        assertThat(testSummary.getAverageResponseTime()).isNotNull();
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleConcurrencyLimitsCorrectly() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When - Execute with limited concurrency
        long startTime = System.currentTimeMillis();
        var testSummary = loadTestService.executeLoadTest(config).join();
        long endTime = System.currentTimeMillis();

        // Then - Verify test completed within reasonable time bounds
        long executionTime = endTime - startTime;
        assertThat(executionTime).isGreaterThan(1000); // Should take at least 1 second with concurrency limits
        assertThat(executionTime).isLessThan(60000); // Should complete within 1 minute

        // Verify metrics show reasonable results
        assertThat(testSummary.getTotalOperations()).isGreaterThan(0);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldCollectMetricsForSuccessfulOperations() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When
        var testSummary = loadTestService.executeLoadTest(config).join();

        // Then
        assertThat(testSummary.getTotalOperations()).isGreaterThan(0);
        assertThat(testSummary.getTotalSuccesses()).isGreaterThan(0);
        assertThat(testSummary.getAverageResponseTime()).isNotNull();

        // Success rate should be high for LocalStack
        double successRate = (double) testSummary.getTotalSuccesses() / testSummary.getTotalOperations();
        assertThat(successRate).isGreaterThan(0.8); // At least 80% success rate
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleDuplicateKeyInjection() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();
        assertThat(config.getDuplicatePercentage()).isGreaterThan(0.0);

        // When
        var testSummary = loadTestService.executeLoadTest(config).join();

        // Then - Should have some operations completed
        assertThat(testSummary.getTotalOperations()).isGreaterThan(0);

        // Verify items were written (some may be duplicates overwriting previous ones)
        ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .build()).join();
        assertThat(scanResponse.items()).isNotEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldLoadConfigurationFromSSMCorrectly() {
        // When
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then
        assertThat(config).isNotNull();
        assertThat(config.getTableName()).isEqualTo(TEST_TABLE_NAME);
        assertThat(config.getConcurrencyLimit()).isEqualTo(5);
        assertThat(config.getTotalItems()).isEqualTo(50);
        assertThat(config.getMaxConcurrencyPercentage()).isEqualTo(80.0);
        assertThat(config.getDuplicatePercentage()).isGreaterThan(0.0);
        assertThat(config.getEnvironment()).isNotNull();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldDetectLocalEnvironmentCorrectly() {
        // When
        boolean isLocal = configurationManager.isLocalEnvironment();

        // Then
        assertThat(isLocal).isTrue();
    }
}