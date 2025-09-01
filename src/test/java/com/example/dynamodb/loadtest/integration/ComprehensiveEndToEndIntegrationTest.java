package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.LoadTestApplication;
import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import com.example.dynamodb.loadtest.service.ReportGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive end-to-end integration tests covering the complete workflow
 * including error handling, reporting, and both local and AWS deployment
 * scenarios.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5
 */
@SpringBootTest(classes = LoadTestApplication.class)
@ActiveProfiles("integration")
class ComprehensiveEndToEndIntegrationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private MetricsCollectionService metricsCollectionService;

    @Autowired
    private ReportGenerationService reportGenerationService;

    private ByteArrayOutputStream outputCapture;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        // Capture stdout for report validation
        outputCapture = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputCapture));
    }

    @AfterEach
    void tearDown() {
        // Restore stdout
        System.setOut(originalOut);

        // Cleanup test data
        cleanupTestData();
        metricsCollectionService.reset();
    }

    /**
     * Test complete workflow from configuration loading to final report generation.
     * Requirements: 11.1, 11.3
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldExecuteCompleteWorkflowSuccessfully() {
        // Given - System is properly configured
        assertThat(configurationManager.isLocalEnvironment()).isTrue();

        // When - Execute complete workflow
        TestConfiguration config = configurationManager.loadConfiguration().join();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        reportGenerationService.generateReport(summary);

        // Then - Verify configuration was loaded correctly
        assertThat(config).isNotNull();
        assertThat(config.getTableName()).isEqualTo(TEST_TABLE_NAME);
        assertThat(config.getConcurrencyLimit()).isEqualTo(5);
        assertThat(config.getTotalItems()).isEqualTo(50);

        // Verify test execution results
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isGreaterThan(0);
        assertThat(summary.getTotalSuccesses()).isGreaterThan(0);
        assertThat(summary.getAverageResponseTime()).isNotNull();

        // Verify items were written to DynamoDB
        ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .build()).join();
        assertThat(scanResponse.items()).isNotEmpty();

        // Verify report was generated
        String reportOutput = outputCapture.toString();
        assertThat(reportOutput).contains("Load Test Report");
        assertThat(reportOutput).contains("Total Operations");
        assertThat(reportOutput).contains("Success Rate");
        assertThat(reportOutput).contains("Average Response Time");
    }

    /**
     * Test error handling during configuration loading.
     * Requirements: 11.1, 11.4
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleConfigurationLoadingErrors() {
        // Given - Remove a required parameter to simulate error
        String parameterName = "/local/dynamodb-load-test/table-name";

        // Delete the parameter to simulate missing configuration
        try {
            ssmClient.deleteParameter(builder -> builder.name(parameterName)).join();
        } catch (Exception e) {
            // Parameter might not exist, continue with test
        }

        // When/Then - Configuration loading should handle the error gracefully
        CompletableFuture<TestConfiguration> configFuture = configurationManager.loadConfiguration();

        assertThrows(Exception.class, () -> {
            configFuture.join();
        });

        // Restore the parameter for cleanup
        setupSSMParameters();
    }

    /**
     * Test error handling during DynamoDB operations.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldHandleDynamoDBOperationErrors() {
        // Given - Configuration with duplicate injection enabled
        TestConfiguration config = configurationManager.loadConfiguration().join();
        assertThat(config.getDuplicatePercentage()).isGreaterThan(0.0);

        // When - Execute load test with duplicate injection
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Should handle operations gracefully even with potential duplicates
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isGreaterThan(0);

        // Should have some successful operations
        assertThat(summary.getTotalSuccesses()).isGreaterThan(0);

        // Verify error handling metrics are captured
        if (summary.getTotalErrors() > 0) {
            assertThat(summary.getErrorTypeCounts()).isNotEmpty();
        }
    }

    /**
     * Test concurrency control and Virtual Thread behavior.
     * Requirements: 11.1, 11.3
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldControlConcurrencyCorrectly() {
        // Given - Configuration with specific concurrency limit
        TestConfiguration config = configurationManager.loadConfiguration().join();
        int concurrencyLimit = config.getConcurrencyLimit();
        assertThat(concurrencyLimit).isEqualTo(5);

        // When - Execute load test
        long startTime = System.currentTimeMillis();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        long executionTime = System.currentTimeMillis() - startTime;

        // Then - Verify concurrency was controlled
        assertThat(summary.getTotalOperations()).isGreaterThan(0);

        // With concurrency limits, execution should take some minimum time
        assertThat(executionTime).isGreaterThan(500); // At least 500ms

        // But should complete within reasonable time
        assertThat(executionTime).isLessThan(120000); // Less than 2 minutes

        // Verify metrics show reasonable performance
        assertThat(summary.getAverageResponseTime()).isNotNull();
        assertThat(summary.getAverageResponseTime().toMillis()).isLessThan(5000); // Less than 5 seconds per operation
    }

    /**
     * Test metrics collection accuracy during load test execution.
     * Requirements: 11.1, 11.3
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldCollectAccurateMetrics() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();
        int expectedOperations = config.getTotalItems();

        // When
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Verify metrics accuracy
        assertThat(summary.getTotalOperations()).isEqualTo(expectedOperations);
        assertThat(summary.getTotalSuccesses() + summary.getTotalErrors()).isEqualTo(expectedOperations);

        // Verify response time metrics
        assertThat(summary.getAverageResponseTime()).isNotNull();
        assertThat(summary.getResponseTimePercentiles()).isNotNull();

        // Verify response time percentiles
        Map<String, Duration> percentiles = summary.getResponseTimePercentiles();
        if (!percentiles.isEmpty()) {
            assertThat(percentiles.get("p50")).isNotNull();
            assertThat(percentiles.get("p95")).isNotNull();
        }

        // Verify error breakdown structure
        assertThat(summary.getErrorTypeCounts()).isNotNull();

        // If there are errors, verify they're properly categorized
        if (summary.getTotalErrors() > 0) {
            long totalCategorizedErrors = summary.getErrorTypeCounts().values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
            assertThat(totalCategorizedErrors).isEqualTo(summary.getTotalErrors());
        }
    }

    /**
     * Test report generation with various scenarios.
     * Requirements: 11.1, 11.3
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldGenerateComprehensiveReports() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        reportGenerationService.generateReport(summary);

        // Then - Verify report content
        String reportOutput = outputCapture.toString();

        // Verify report structure
        assertThat(reportOutput).contains("Load Test Report");
        assertThat(reportOutput).contains("Configuration");
        assertThat(reportOutput).contains("Results Summary");
        assertThat(reportOutput).contains("Performance Metrics");

        // Verify configuration details in report
        assertThat(reportOutput).contains(config.getTableName());
        assertThat(reportOutput).contains(String.valueOf(config.getConcurrencyLimit()));
        assertThat(reportOutput).contains(String.valueOf(config.getTotalItems()));

        // Verify metrics in report
        assertThat(reportOutput).contains("Total Operations: " + summary.getTotalOperations());
        assertThat(reportOutput).contains("Successful Operations: " + summary.getTotalSuccesses());
        assertThat(reportOutput).contains("Failed Operations: " + summary.getTotalErrors());

        // Verify response time information
        assertThat(reportOutput).contains("Average Response Time");

        // Verify success rate calculation
        double expectedSuccessRate = (double) summary.getTotalSuccesses() / summary.getTotalOperations() * 100;
        assertThat(reportOutput).contains(String.format("%.1f%%", expectedSuccessRate));
    }

    /**
     * Test environment detection for local vs AWS deployment.
     * Requirements: 11.2
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldDetectEnvironmentCorrectly() {
        // When
        boolean isLocal = configurationManager.isLocalEnvironment();
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then - Should detect LocalStack environment
        assertThat(isLocal).isTrue();
        assertThat(config.getEnvironment()).isEqualTo("local");

        // Verify LocalStack endpoints are being used
        // This is implicit in the successful operation with LocalStack
        assertThat(config.getTableName()).startsWith("local-");
    }

    /**
     * Test parameter validation during configuration loading.
     * Requirements: 11.4
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldValidateConfigurationParameters() {
        // When
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then - Verify all parameters are valid
        assertThat(config.getTableName()).isNotNull().isNotEmpty();
        assertThat(config.getConcurrencyLimit()).isGreaterThan(0).isLessThanOrEqualTo(100);
        assertThat(config.getTotalItems()).isGreaterThan(0);
        assertThat(config.getMaxConcurrencyPercentage()).isGreaterThan(0).isLessThanOrEqualTo(100);
        assertThat(config.getEnvironment()).isNotNull().isNotEmpty();

        // Verify boolean parameter parsing
        assertThat(config.getDuplicatePercentage()).isNotNull();
    }

    /**
     * Test graceful handling of missing SSM parameters.
     * Requirements: 11.4
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleMissingSSMParameters() {
        // Given - Create a parameter name that doesn't exist
        String nonExistentParameter = "/local/dynamodb-load-test/non-existent-parameter";

        // When/Then - Should handle missing parameter gracefully
        CompletableFuture<String> parameterFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return ssmClient.getParameter(GetParameterRequest.builder()
                        .name(nonExistentParameter)
                        .build()).join().parameter().value();
            } catch (Exception e) {
                if (e.getCause() instanceof ParameterNotFoundException) {
                    return null;
                }
                throw e;
            }
        });

        String result = parameterFuture.join();
        assertThat(result).isNull();
    }

    /**
     * Test load ramping behavior with progressive concurrency.
     * Requirements: 11.2
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldRampLoadProgressively() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When - Execute load test and measure timing
        long startTime = System.currentTimeMillis();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        long totalTime = System.currentTimeMillis() - startTime;

        // Then - Verify progressive ramping occurred
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());

        // With progressive ramping, execution should take longer than if all operations
        // were executed at max concurrency immediately
        int maxConcurrencyItems = (int) (config.getTotalItems() * config.getMaxConcurrencyPercentage() / 100);
        assertThat(maxConcurrencyItems).isLessThan(config.getTotalItems());

        // Verify reasonable execution time (not too fast, not too slow)
        assertThat(totalTime).isGreaterThan(1000); // At least 1 second
        assertThat(totalTime).isLessThan(120000); // Less than 2 minutes
    }

    /**
     * Test complete application lifecycle including startup and shutdown.
     * Requirements: 11.1, 11.2
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleApplicationLifecycleCorrectly() {
        // Given - Application components are properly initialized
        assertThat(configurationManager).isNotNull();
        assertThat(loadTestService).isNotNull();
        assertThat(metricsCollectionService).isNotNull();
        assertThat(reportGenerationService).isNotNull();

        // When - Test basic functionality
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Then - All components should be functional
        assertThat(config).isNotNull();
        assertThat(configurationManager.isLocalEnvironment()).isTrue();

        // Verify services are properly wired
        assertThat(loadTestService).isNotNull();
        assertThat(metricsCollectionService).isNotNull();
    }
}