package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration tests for AWS deployment scenarios.
 * These tests are designed to run against real AWS services when AWS
 * credentials are available.
 * 
 * Requirements: 11.1, 11.2
 * 
 * Note: These tests are disabled by default and only run when AWS_REGION
 * environment variable is set.
 * To run these tests:
 * 1. Set up AWS credentials (AWS CLI, IAM role, or environment variables)
 * 2. Set AWS_REGION environment variable
 * 3. Ensure the test environment has proper DynamoDB and SSM permissions
 */
@SpringBootTest
@ActiveProfiles("aws-integration")
@EnabledIfEnvironmentVariable(named = "AWS_REGION", matches = ".*")
class AWSDeploymentScenarioTest {

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private DynamoDbAsyncClient dynamoDbClient;

    @Autowired
    private SsmAsyncClient ssmClient;

    @BeforeEach
    void setUp() {
        // Verify AWS environment is properly configured
        assertThat(System.getenv("AWS_REGION")).isNotNull();

        // Note: In real AWS deployment scenarios, the infrastructure would be
        // set up via CloudFormation. For testing, we assume the table and
        // parameters exist or we create them programmatically.
    }

    @AfterEach
    void tearDown() {
        // Cleanup test data from AWS resources
        cleanupAWSTestData();
    }

    /**
     * Test configuration loading from real AWS SSM Parameter Store.
     * Requirements: 11.2, 11.4
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldLoadConfigurationFromAWSSSM() {
        // Given - AWS SSM parameters exist (assumed to be set up)

        // When
        TestConfiguration config = assertDoesNotThrow(() -> {
            return configurationManager.loadConfiguration().join();
        });

        // Then
        assertThat(config).isNotNull();
        assertThat(config.getEnvironment()).isNotEqualTo("local");
        assertThat(config.getTableName()).isNotNull().isNotEmpty();
        assertThat(config.getConcurrencyLimit()).isGreaterThan(0);
        assertThat(config.getTotalItems()).isGreaterThan(0);

        // Verify environment detection
        assertThat(configurationManager.isLocalEnvironment()).isFalse();
    }

    /**
     * Test DynamoDB operations against real AWS DynamoDB.
     * Requirements: 11.2, 11.5
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void shouldExecuteLoadTestAgainstAWSDynamoDB() {
        // Given - Configuration loaded from AWS
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Verify table exists
        assertDoesNotThrow(() -> {
            DescribeTableResponse response = dynamoDbClient.describeTable(
                    DescribeTableRequest.builder()
                            .tableName(config.getTableName())
                            .build())
                    .join();
            assertThat(response.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);
        });

        // When - Execute load test
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());
        assertThat(summary.getTotalSuccesses()).isGreaterThan(0);

        // Verify items were written to DynamoDB
        ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(config.getTableName())
                .limit(10) // Limit to avoid large scans in real AWS
                .build()).join();
        assertThat(scanResponse.items()).isNotEmpty();
    }

    /**
     * Test error handling with real AWS throttling and capacity limits.
     * Requirements: 11.2, 11.5
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void shouldHandleAWSThrottlingAndCapacityLimits() {
        // Given - Configuration with higher load to potentially trigger throttling
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Create a configuration that might trigger capacity limits
        TestConfiguration highLoadConfig = new TestConfiguration(
                config.getTableName(),
                Math.max(config.getConcurrencyLimit(), 20), // Higher concurrency
                Math.max(config.getTotalItems(), 1000), // More items
                config.getMaxConcurrencyPercentage(),
                config.getDuplicatePercentage(),
                true, // Enable cleanup
                config.getEnvironment());

        // When - Execute high-load test
        TestSummary summary = loadTestService.executeLoadTest(highLoadConfig).join();

        // Then - Should handle any throttling gracefully
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isEqualTo(highLoadConfig.getTotalItems());

        // May have some errors due to throttling, but should have some successes
        assertThat(summary.getTotalSuccesses()).isGreaterThan(0);

        // If there are capacity errors, they should be properly categorized
        if (summary.getTotalErrors() > 0) {
            assertThat(summary.getErrorTypeCounts()).isNotEmpty();

            // Check for capacity-related errors
            boolean hasCapacityErrors = summary.getErrorTypeCounts().keySet().stream()
                    .anyMatch(errorType -> errorType.toLowerCase().contains("capacity") ||
                            errorType.toLowerCase().contains("throttl"));

            if (hasCapacityErrors) {
                // Verify error handling worked correctly
                assertThat(summary.getErrorTypeCounts()).containsKey("CAPACITY_EXCEEDED");
            }
        }
    }

    /**
     * Test metrics collection in AWS environment.
     * Requirements: 11.2
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldCollectMetricsInAWSEnvironment() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When - Execute load test (metrics should be collected automatically)
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Verify test completed successfully and metrics were collected
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isGreaterThan(0);
        assertThat(summary.getTotalSuccesses()).isGreaterThanOrEqualTo(0);
        assertThat(summary.getAverageResponseTime()).isNotNull();
        assertThat(summary.getThroughputPerSecond()).isGreaterThan(0);

        // In a real deployment, you can verify DynamoDB built-in metrics
        // are available in the CloudWatch dashboard for monitoring
    }

    /**
     * Test parameter validation with real AWS SSM parameters.
     * Requirements: 11.4
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldValidateAWSSSMParameters() {
        // Given - Real AWS environment
        String environment = System.getenv("AWS_REGION").equals("us-east-1") ? "prod" : "test";

        // When - Load individual parameters
        String tableNameParam = "/" + environment + "/dynamodb-load-test/table-name";
        String concurrencyParam = "/" + environment + "/dynamodb-load-test/concurrency-limit";

        // Then - Parameters should be accessible and valid
        assertDoesNotThrow(() -> {
            String tableName = ssmClient.getParameter(GetParameterRequest.builder()
                    .name(tableNameParam)
                    .build()).join().parameter().value();
            assertThat(tableName).isNotNull().isNotEmpty();
        });

        assertDoesNotThrow(() -> {
            String concurrency = ssmClient.getParameter(GetParameterRequest.builder()
                    .name(concurrencyParam)
                    .build()).join().parameter().value();
            int concurrencyValue = Integer.parseInt(concurrency);
            assertThat(concurrencyValue).isGreaterThan(0).isLessThanOrEqualTo(100);
        });
    }

    /**
     * Test network resilience with real AWS endpoints.
     * Requirements: 11.2, 11.5
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void shouldHandleNetworkIssuesGracefully() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When - Execute load test (network issues may occur naturally)
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Should complete despite potential network hiccups
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());

        // Should have high success rate even with potential network issues
        double successRate = (double) summary.getTotalSuccesses() / summary.getTotalOperations();
        assertThat(successRate).isGreaterThan(0.7); // At least 70% success rate

        // Any network errors should be properly categorized
        if (summary.getTotalErrors() > 0) {
            assertThat(summary.getErrorTypeCounts()).isNotEmpty();
        }
    }

    /**
     * Test performance characteristics in AWS environment.
     * Requirements: 11.2
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void shouldMeetPerformanceExpectationsInAWS() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When
        long startTime = System.currentTimeMillis();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        long totalTime = System.currentTimeMillis() - startTime;

        // Then - Verify performance characteristics
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());

        // Response times should be reasonable for AWS
        assertThat(summary.getAverageResponseTime().toMillis()).isLessThan(10000); // Less than 10 seconds
        assertThat(summary.getAverageResponseTime().toMillis()).isGreaterThan(0);

        // Total execution time should be reasonable
        double operationsPerSecond = (double) summary.getTotalOperations() / (totalTime / 1000.0);
        assertThat(operationsPerSecond).isGreaterThan(0.1); // At least 0.1 ops/sec

        // Success rate should be high in AWS
        double successRate = (double) summary.getTotalSuccesses() / summary.getTotalOperations();
        assertThat(successRate).isGreaterThan(0.8); // At least 80% success rate
    }

    private void cleanupAWSTestData() {
        try {
            // In a real scenario, you might want to clean up test data
            // For safety, we don't automatically delete data from AWS
            // This would typically be handled by test environment teardown

            // Example cleanup (commented out for safety):
            // dynamoDbClient.scan(ScanRequest.builder()
            // .tableName(TEST_TABLE_NAME)
            // .build())
            // .thenCompose(response -> {
            // // Delete items created during test
            // return CompletableFuture.allOf(/* delete operations */);
            // });

        } catch (Exception e) {
            // Log cleanup errors but don't fail the test
            System.err.println("Warning: Could not clean up AWS test data: " + e.getMessage());
        }
    }
}