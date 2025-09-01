package com.example.dynamodb.loadtest;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import com.example.dynamodb.loadtest.service.ReportGenerationService;
import com.example.dynamodb.loadtest.service.AccurateDuplicateCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.dynamodb.loadtest.config.AWSConfigurationProperties;
import com.example.dynamodb.loadtest.config.DynamoDBConfigurationProperties;
import com.example.dynamodb.loadtest.config.SSMConfigurationProperties;
import com.example.dynamodb.loadtest.config.AppConfigurationProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication
@EnableConfigurationProperties({
        AWSConfigurationProperties.class,
        DynamoDBConfigurationProperties.class,
        SSMConfigurationProperties.class,
        AppConfigurationProperties.class
})
public class LoadTestApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(LoadTestApplication.class);

    private final ConfigurationManager configurationManager;
    private final LoadTestService loadTestService;
    private final ReportGenerationService reportGenerationService;
    private final AccurateDuplicateCounter accurateDuplicateCounter;

    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor();
    private CompletableFuture<TestSummary> currentTestExecution;
    private Instant testStartTime;

    public LoadTestApplication(
            ConfigurationManager configurationManager,
            LoadTestService loadTestService,
            ReportGenerationService reportGenerationService,
            AccurateDuplicateCounter accurateDuplicateCounter) {
        this.configurationManager = configurationManager;
        this.loadTestService = loadTestService;
        this.reportGenerationService = reportGenerationService;
        this.accurateDuplicateCounter = accurateDuplicateCounter;

        // Register shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(this::initiateGracefulShutdown));
    }

    @PostConstruct
    public void init() {
        // Set up structured logging context
        MDC.put("service", "dynamodb-load-test");
        MDC.put("version", "1.0.0");

        logger.info("DynamoDB Load Test batch application initialized");
    }

    public static void main(String[] args) {
        // Batch application - no web server needed
        logger.info("Starting DynamoDB Load Test batch application");
        System.setProperty("spring.main.web-application-type", "none");

        ConfigurableApplicationContext context = SpringApplication.run(LoadTestApplication.class, args);

        // Ensure proper shutdown with appropriate exit code
        int exitCode = SpringApplication.exit(context);
        System.exit(exitCode);
    }

    @Override
    public void run(String... args) throws Exception {
        // Set up structured logging context for this execution
        MDC.put("execution.id", java.util.UUID.randomUUID().toString());
        MDC.put("execution.start", Instant.now().toString());

        logger.info("Starting DynamoDB Load Test Application");

        try {
            // Load configuration from SSM Parameter Store
            logger.info("Loading configuration...");
            TestConfiguration config = configurationManager.loadConfiguration().get();

            if (shutdownRequested.get()) {
                logger.info("Shutdown requested during configuration loading. Exiting gracefully.");
                return;
            }

            // Add configuration to logging context
            MDC.put("config.table", config.getTableName());
            MDC.put("config.items", String.valueOf(config.getTotalItems()));
            MDC.put("config.concurrency", String.valueOf(config.getConcurrencyLimit()));

            logger.info("Configuration loaded successfully for environment: {}", config.getEnvironment());
            logger.info("Test configuration: Table={}, Items={}, Concurrency={}, DuplicatePercentage={}%, Cleanup={}",
                    config.getTableName(),
                    config.getTotalItems(),
                    config.getConcurrencyLimit(),
                    config.getDuplicatePercentage(),
                    config.getCleanupAfterTest());

            // Start progress reporting
            startProgressReporting(config);

            // Execute the load test
            logger.info("Starting load test execution...");
            testStartTime = Instant.now();
            MDC.put("test.start", testStartTime.toString());

            currentTestExecution = loadTestService.executeLoadTest(config);

            // Wait for completion and get results
            TestSummary summary = currentTestExecution.get();

            if (shutdownRequested.get()) {
                logger.info("Test completed during shutdown request.");
            }

            // Stop progress reporting
            stopProgressReporting();

            // Add test results to logging context
            MDC.put("test.end", Instant.now().toString());
            MDC.put("test.duration", Duration.between(testStartTime, Instant.now()).toString());

            // Generate and display the report
            logger.info("Load test completed. Generating report...");

            // Calculate accurate duplicate errors if duplicates were injected
            if (config.getDuplicatePercentage() > 0) {
                try {
                    int accurateDuplicateErrors = accurateDuplicateCounter.calculateAccurateDuplicateErrors(
                            config.getTableName(),
                            config.getTotalItems(),
                            config.getDuplicatePercentage());

                    if (accurateDuplicateErrors >= 0) {
                        logger.info("Accurate duplicate error count: {} (reported: {})",
                                accurateDuplicateErrors, summary.getTotalErrors());

                        // Update summary with accurate duplicate count if significantly different
                        if (Math.abs(accurateDuplicateErrors - summary.getTotalErrors()) > 1) {
                            logger.warn(
                                    "Significant discrepancy detected between reported ({}) and actual ({}) duplicate errors",
                                    summary.getTotalErrors(), accurateDuplicateErrors);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to calculate accurate duplicate errors", e);
                }
            }

            reportGenerationService.generateReport(summary);
            logger.info("Load test report generated successfully");

            // Perform cleanup if enabled
            if (config.getCleanupAfterTest() != null && config.getCleanupAfterTest()) {
                logger.info("Cleanup after test is enabled. Starting cleanup...");
                try {
                    loadTestService.cleanupTestData(config).get();
                    logger.info("Test data cleanup completed successfully");
                } catch (Exception cleanupException) {
                    logger.error("Failed to cleanup test data", cleanupException);
                    // Don't fail the entire application if cleanup fails
                }
            } else {
                logger.info("Cleanup after test is disabled. Test data will remain in the table.");
            }

            logger.info("DynamoDB Load Test Application completed successfully");

        } catch (InterruptedException e) {
            logger.info("Load test execution was interrupted. Initiating graceful shutdown...");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Load test execution failed", e);
            System.err.println("Error: " + e.getMessage());
            throw e;
        } finally {
            cleanup();
            MDC.clear();
        }
    }

    /**
     * Starts periodic progress reporting during test execution.
     */
    private void startProgressReporting(TestConfiguration config) {
        logger.info("Starting progress reporting (updates every 30 seconds)");

        progressReporter.scheduleAtFixedRate(() -> {
            if (!shutdownRequested.get() && currentTestExecution != null && !currentTestExecution.isDone()) {
                Duration elapsed = Duration.between(testStartTime, Instant.now());
                logger.info("Load test in progress... Elapsed time: {}m {}s, Target items: {}, Concurrency: {}",
                        elapsed.toMinutes(),
                        elapsed.getSeconds() % 60,
                        config.getTotalItems(),
                        config.getConcurrencyLimit());

                System.out.println(String.format("Progress: Test running for %dm %ds...",
                        elapsed.toMinutes(), elapsed.getSeconds() % 60));
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Stops progress reporting.
     */
    private void stopProgressReporting() {
        if (!progressReporter.isShutdown()) {
            progressReporter.shutdown();
            try {
                if (!progressReporter.awaitTermination(5, TimeUnit.SECONDS)) {
                    progressReporter.shutdownNow();
                }
            } catch (InterruptedException e) {
                progressReporter.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Initiates graceful shutdown when SIGTERM or SIGINT is received.
     */
    private void initiateGracefulShutdown() {
        if (shutdownRequested.compareAndSet(false, true)) {
            logger.info("Shutdown signal received. Initiating graceful shutdown...");

            if (currentTestExecution != null && !currentTestExecution.isDone()) {
                logger.info("Waiting for current load test to complete...");
                System.out.println("Graceful shutdown initiated. Waiting for test to complete...");

                try {
                    // Wait up to 2 minutes for test to complete naturally
                    currentTestExecution.get(2, TimeUnit.MINUTES);
                    logger.info("Load test completed during graceful shutdown");
                } catch (Exception e) {
                    logger.warn("Load test did not complete within shutdown timeout. Forcing termination.", e);
                    currentTestExecution.cancel(true);
                }
            }

            cleanup();
            logger.info("Graceful shutdown completed");
        }
    }

    /**
     * Cleanup resources and stop background tasks.
     */
    @PreDestroy
    public void cleanup() {
        logger.debug("Cleaning up application resources");
        stopProgressReporting();

        if (currentTestExecution != null && !currentTestExecution.isDone()) {
            currentTestExecution.cancel(true);
        }
    }

    /**
     * Checks if shutdown has been requested.
     * 
     * @return true if shutdown is in progress
     */
    public boolean isShutdownRequested() {
        return shutdownRequested.get();
    }
}