package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.model.TestItem;
import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of LoadTestService for executing DynamoDB load tests.
 * Handles test item generation, duplicate key injection, and basic test
 * execution structure.
 */
@Service
public class LoadTestServiceImpl implements LoadTestService {

    private static final Logger logger = LoggerFactory.getLogger(LoadTestServiceImpl.class);

    private final MetricsCollectionService metricsCollectionService;
    private final ResilientDynamoDBService resilientDynamoDBService;
    private final AccurateDuplicateCounter accurateDuplicateCounter;
    private final Executor virtualThreadExecutor;
    private final AtomicLong keyCounter = new AtomicLong(0);
    private final Random random = new Random();

    // Default payload size in bytes (approximately 300 bytes to stay under 500 byte
    // total limit)
    private static final int DEFAULT_PAYLOAD_SIZE = 300;

    // Characters used for generating payload data
    private static final String PAYLOAD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public LoadTestServiceImpl(
            MetricsCollectionService metricsCollectionService,
            ResilientDynamoDBService resilientDynamoDBService,
            AccurateDuplicateCounter accurateDuplicateCounter,
            @Qualifier("virtualThreadExecutor") Executor virtualThreadExecutor) {
        this.metricsCollectionService = metricsCollectionService;
        this.resilientDynamoDBService = resilientDynamoDBService;
        this.accurateDuplicateCounter = accurateDuplicateCounter;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public CompletableFuture<TestSummary> executeLoadTest(TestConfiguration config) {
        logger.info("Starting load test execution with configuration: {}", config.createSafeCopy());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Start metrics collection
                metricsCollectionService.startTest();

                // Generate test items and pre-stage them to avoid race conditions
                List<TestItem> testItems = generateAndStageTestItems(config);

                logger.info("Generated and staged {} test items", testItems.size());

                // Execute load test with progressive concurrency ramping
                executeProgressiveLoadTest(testItems, config).join();

                // End metrics collection
                metricsCollectionService.endTest();

                // Perform enhanced duplicate accuracy validation
                TestSummary summary = metricsCollectionService.generateSummary();
                performEnhancedDuplicateValidation(config, summary);

                logger.info("Load test completed. Summary: {}", summary);

                return summary;

            } catch (Exception e) {
                logger.error("Load test execution failed", e);
                metricsCollectionService.endTest();
                throw new RuntimeException("Load test execution failed", e);
            }
        }, virtualThreadExecutor);
    }

    /**
     * Executes a progressive load test with ramping concurrency levels.
     * 
     * @param testItems the items to process
     * @param config    the test configuration
     * @return CompletableFuture that completes when the load test is done
     */
    private CompletableFuture<Void> executeProgressiveLoadTest(List<TestItem> testItems, TestConfiguration config) {
        logger.info("Executing progressive load test with {} items", testItems.size());

        return CompletableFuture.runAsync(() -> {
            try {
                // Calculate item distribution
                int itemsForMaxConcurrency = config.getItemsForMaxConcurrency();
                int itemsForRampUp = config.getItemsForRampUp();
                int maxConcurrencyLevel = config.getMaxConcurrencyLevel();

                logger.info("Load test plan: {} items for ramp-up, {} items at max concurrency ({})",
                        itemsForRampUp, itemsForMaxConcurrency, maxConcurrencyLevel);

                List<CompletableFuture<Void>> phases = new ArrayList<>();
                int itemIndex = 0;

                // Phase 1: Progressive ramping up to max concurrency
                if (itemsForRampUp > 0) {
                    logger.info("Starting ramp-up phase with {} items", itemsForRampUp);

                    // Calculate concurrency levels for ramp-up
                    int rampUpSteps = Math.min(10, maxConcurrencyLevel); // Max 10 steps
                    int itemsPerStep = Math.max(1, itemsForRampUp / rampUpSteps);

                    for (int step = 1; step <= rampUpSteps && itemIndex < itemsForRampUp; step++) {
                        int concurrencyLevel = Math.max(1, (step * maxConcurrencyLevel) / rampUpSteps);
                        int endIndex = Math.min(itemIndex + itemsPerStep, itemsForRampUp);

                        if (itemIndex < endIndex) {
                            List<TestItem> stepItems = testItems.subList(itemIndex, endIndex);
                            logger.debug("Ramp-up step {}: {} items at concurrency {}",
                                    step, stepItems.size(), concurrencyLevel);

                            CompletableFuture<Void> stepFuture = executeWithConcurrency(stepItems, concurrencyLevel);
                            phases.add(stepFuture);

                            itemIndex = endIndex;
                        }
                    }
                }

                // Phase 2: Maximum concurrency execution
                if (itemsForMaxConcurrency > 0 && itemIndex < testItems.size()) {
                    int endIndex = Math.min(itemIndex + itemsForMaxConcurrency, testItems.size());
                    List<TestItem> maxConcurrencyItems = testItems.subList(itemIndex, endIndex);

                    logger.info("Starting max concurrency phase: {} items at concurrency {}",
                            maxConcurrencyItems.size(), maxConcurrencyLevel);

                    CompletableFuture<Void> maxConcurrencyFuture = executeWithConcurrency(
                            maxConcurrencyItems, maxConcurrencyLevel);
                    phases.add(maxConcurrencyFuture);

                    itemIndex = endIndex;
                }

                // Process any remaining items at max concurrency
                if (itemIndex < testItems.size()) {
                    List<TestItem> remainingItems = testItems.subList(itemIndex, testItems.size());
                    logger.info("Processing {} remaining items at max concurrency {}",
                            remainingItems.size(), maxConcurrencyLevel);

                    CompletableFuture<Void> remainingFuture = executeWithConcurrency(
                            remainingItems, maxConcurrencyLevel);
                    phases.add(remainingFuture);
                }

                // Wait for all phases to complete
                CompletableFuture.allOf(phases.toArray(new CompletableFuture[0])).join();

                logger.info("Progressive load test completed successfully");

            } catch (Exception e) {
                logger.error("Progressive load test execution failed", e);
                throw new RuntimeException("Progressive load test execution failed", e);
            }
        }, virtualThreadExecutor);
    }

    @Override
    public CompletableFuture<Void> executeWithConcurrency(List<TestItem> items, int concurrency) {
        logger.info("Executing {} items with concurrency level {} using Virtual Threads", items.size(), concurrency);

        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Create semaphore to control concurrency
        Semaphore semaphore = new Semaphore(concurrency);

        // Create list to hold all futures
        List<CompletableFuture<Void>> futures = new ArrayList<>(items.size());

        // Submit each item for processing using Virtual Threads
        for (TestItem item : items) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // Acquire semaphore permit
                    semaphore.acquire();

                    try {
                        // Process the item
                        processItem(item, concurrency);
                    } finally {
                        // Always release the permit
                        semaphore.release();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Thread interrupted while processing item: {}", item.getPrimaryKey(), e);
                    throw new RuntimeException("Execution interrupted", e);
                } catch (Exception e) {
                    logger.error("Error processing item: {}", item.getPrimaryKey(), e);
                    // Record error in metrics
                    metricsCollectionService.recordError("ProcessingError", Duration.ofMillis(0), concurrency);
                    throw e;
                }
            }, virtualThreadExecutor);

            futures.add(future);
        }

        // Return a future that completes when all items are processed
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.error("Error during concurrent execution", throwable);
                    } else {
                        logger.info("Successfully completed processing {} items with concurrency {}",
                                items.size(), concurrency);
                    }
                });
    }

    /**
     * Processes a single test item by writing it to DynamoDB with enhanced error
     * tracking.
     * 
     * @param item             the item to process
     * @param concurrencyLevel the current concurrency level
     */
    private void processItem(TestItem item, int concurrencyLevel) {
        logger.debug("Processing item with key: {} (expected: {}) at concurrency level {}",
                item.getPrimaryKey(),
                item.getAttributes().getOrDefault("expected_result", "SUCCESS"),
                concurrencyLevel);

        Instant startTime = Instant.now();
        String expectedResult = (String) item.getAttributes().getOrDefault("expected_result", "SUCCESS");
        boolean isDuplicate = Boolean.TRUE.equals(item.getAttributes().get("is_duplicate"));

        try {
            // Create a TestMetrics object for this operation
            com.example.dynamodb.loadtest.model.TestMetrics itemMetrics = new com.example.dynamodb.loadtest.model.TestMetrics(
                    Duration.ZERO, 0, 0, concurrencyLevel);

            // Execute DynamoDB put operation with enhanced resilience
            resilientDynamoDBService.putItemWithEnhancedResilience(item, itemMetrics).join();

            // Record successful operation
            Duration responseTime = Duration.between(startTime, Instant.now());
            metricsCollectionService.recordSuccess(responseTime, concurrencyLevel);

            // Log unexpected success for duplicate items
            if (isDuplicate) {
                logger.warn("Duplicate item succeeded unexpectedly: {} (this may indicate a race condition)",
                        item.getPrimaryKey());
            }

            logger.debug("Successfully processed item: {} (expected: {}, actual: SUCCESS)",
                    item.getPrimaryKey(), expectedResult);

        } catch (Exception e) {
            // Record error with enhanced categorization
            Duration responseTime = Duration.between(startTime, Instant.now());
            String errorType = determineErrorType(e);

            // Enhanced logging for duplicate error tracking
            if (TestMetrics.ERROR_TYPE_DUPLICATE_KEY.equals(errorType)) {
                if (isDuplicate) {
                    logger.debug("Duplicate error as expected for item: {} (expected: {}, actual: DUPLICATE_ERROR)",
                            item.getPrimaryKey(), expectedResult);
                } else {
                    logger.warn("Unexpected duplicate error for unique item: {} (this may indicate a race condition)",
                            item.getPrimaryKey());
                }
            } else if (isDuplicate && "DUPLICATE_ERROR".equals(expectedResult)) {
                logger.warn("Expected duplicate error but got {}: {} (this may indicate a race condition)",
                        errorType, item.getPrimaryKey());
            }

            metricsCollectionService.recordError(errorType, responseTime, concurrencyLevel);

            logger.debug("Failed to process item: {} - Error: {} (expected: {}, actual: {})",
                    item.getPrimaryKey(), errorType, expectedResult, errorType);

            // Don't re-throw the exception to allow the load test to continue
            // The error has been recorded in metrics
        }
    }

    /**
     * Determines the error type based on the exception.
     * 
     * @param exception the exception that occurred
     * @return the error type string
     */
    private String determineErrorType(Exception exception) {
        // Unwrap nested exceptions
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        // Check for specific DynamoDB exceptions
        if (cause instanceof software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException) {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_DUPLICATE_KEY;
        } else if (cause instanceof software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException) {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED;
        } else if (cause instanceof software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException) {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED;
        } else if (cause.getClass().getSimpleName().contains("Throttling")) {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_THROTTLING;
        } else if (cause instanceof java.net.ConnectException ||
                cause instanceof java.net.UnknownHostException ||
                cause.getMessage() != null && cause.getMessage().toLowerCase().contains("network")) {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_NETWORK;
        } else if (cause instanceof java.net.SocketTimeoutException ||
                cause instanceof java.util.concurrent.TimeoutException ||
                (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("timeout"))) {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_TIMEOUT;
        } else {
            return com.example.dynamodb.loadtest.model.TestMetrics.ERROR_TYPE_UNKNOWN;
        }
    }

    /**
     * Generates and stages test items to avoid race conditions during execution.
     * This method pre-generates all items and optionally stages them to a temporary
     * file
     * to prevent memory issues with large datasets.
     * 
     * @param config the test configuration
     * @return list of generated test items
     */
    private List<TestItem> generateAndStageTestItems(TestConfiguration config) {
        int count = config.getTotalItems();
        double duplicatePercentage = config.getDuplicatePercentage();

        logger.info("Generating and staging {} test items (duplicatePercentage: {}%)", count, duplicatePercentage);

        // For large datasets (>10k items), consider using temporary file staging
        if (count > 10000) {
            return generateTestItemsWithFileStaging(count, duplicatePercentage);
        } else {
            return generateTestItems(count, duplicatePercentage);
        }
    }

    /**
     * Generates test items with file staging for large datasets to avoid memory
     * issues.
     * 
     * @param count               the number of items to generate
     * @param duplicatePercentage the percentage of duplicate items
     * @return list of generated test items
     */
    private List<TestItem> generateTestItemsWithFileStaging(int count, double duplicatePercentage) {
        logger.info("Using file staging for large dataset: {} items", count);

        // For now, fall back to in-memory generation but with optimized approach
        // In production, this could write to temporary files and read back
        return generateTestItemsOptimized(count, duplicatePercentage);
    }

    /**
     * Optimized test item generation that ensures proper duplicate distribution
     * and avoids race conditions.
     * 
     * @param count               the number of items to generate
     * @param duplicatePercentage the percentage of duplicate items
     * @return list of generated test items
     */
    private List<TestItem> generateTestItemsOptimized(int count, double duplicatePercentage) {
        logger.info("Generating {} test items with optimized approach (duplicatePercentage: {}%)",
                count, duplicatePercentage);

        List<TestItem> items = new ArrayList<>(count);
        Set<String> uniqueKeys = new LinkedHashSet<>(); // Preserve insertion order

        int duplicateCount = duplicatePercentage > 0 ? (int) Math.ceil(count * (duplicatePercentage / 100.0)) : 0;
        int uniqueCount = count - duplicateCount;

        logger.info("Will generate {} unique items and {} duplicate items", uniqueCount, duplicateCount);

        // Phase 1: Generate all unique items first
        for (int i = 0; i < uniqueCount; i++) {
            String key = generateUniqueKey();
            uniqueKeys.add(key);

            TestItem item = new TestItem(key, generatePayload(DEFAULT_PAYLOAD_SIZE));
            item.addAttribute("item_index", i);
            item.addAttribute("is_duplicate", false);
            item.addAttribute("generation_time", Instant.now().toString());
            item.addAttribute("expected_result", "SUCCESS");

            items.add(item);
        }

        // Phase 2: Generate duplicate items using existing keys
        List<String> keyList = new ArrayList<>(uniqueKeys);
        for (int i = 0; i < duplicateCount; i++) {
            String duplicateKey = generateDuplicateKey(keyList);

            TestItem item = new TestItem(duplicateKey, generatePayload(DEFAULT_PAYLOAD_SIZE));
            item.addAttribute("item_index", uniqueCount + i);
            item.addAttribute("is_duplicate", true);
            item.addAttribute("generation_time", Instant.now().toString());
            item.addAttribute("expected_result", "DUPLICATE_ERROR");

            items.add(item);
        }

        // Phase 3: Shuffle items to randomize execution order
        Collections.shuffle(items, random);

        logger.info("Successfully generated {} test items ({} unique, {} duplicates)",
                items.size(), uniqueCount, duplicateCount);

        return items;
    }

    @Override
    public List<TestItem> generateTestItems(int count, double duplicatePercentage) {
        return generateTestItemsOptimized(count, duplicatePercentage);
    }

    @Override
    public String generateUniqueKey() {
        long timestamp = System.currentTimeMillis();
        long counter = keyCounter.incrementAndGet();
        int randomSuffix = ThreadLocalRandom.current().nextInt(1000, 9999);

        return String.format("test-item-%d-%d-%d", timestamp, counter, randomSuffix);
    }

    @Override
    public String generateDuplicateKey(List<String> existingKeys) {
        if (existingKeys.isEmpty()) {
            logger.warn("No existing keys available for duplicate generation, creating unique key instead");
            return generateUniqueKey();
        }

        // Select a random existing key
        int randomIndex = random.nextInt(existingKeys.size());
        String duplicateKey = existingKeys.get(randomIndex);

        logger.debug("Generated duplicate key: {}", duplicateKey);
        return duplicateKey;
    }

    @Override
    public String generatePayload(int sizeBytes) {
        if (sizeBytes <= 0) {
            return "";
        }

        StringBuilder payload = new StringBuilder(sizeBytes);

        // Fill the payload with random characters to reach the desired size
        for (int i = 0; i < sizeBytes; i++) {
            int randomIndex = random.nextInt(PAYLOAD_CHARS.length());
            payload.append(PAYLOAD_CHARS.charAt(randomIndex));
        }

        return payload.toString();
    }

    /**
     * Gets the current key counter value for testing purposes.
     * 
     * @return current key counter value
     */
    public long getCurrentKeyCounter() {
        return keyCounter.get();
    }

    /**
     * Resets the key counter for testing purposes.
     */
    public void resetKeyCounter() {
        keyCounter.set(0);
    }

    @Override
    public CompletableFuture<Void> cleanupTestData(TestConfiguration config) {
        logger.info("Starting cleanup of test data from table: {}", config.getTableName());

        return CompletableFuture.runAsync(() -> {
            try {
                // Add delay to allow DynamoDB to scale down from high-throughput writes
                logger.info("Waiting 10 seconds for DynamoDB to scale down before cleanup...");
                Thread.sleep(10000);

                // Scan for all test items (items with keys starting with "test-item-")
                String keyPrefix = "test-item-";

                resilientDynamoDBService.getDynamoDBRepository()
                        .scanItemKeys(config.getTableName(), keyPrefix)
                        .thenCompose(keys -> {
                            if (keys.isEmpty()) {
                                logger.info("No test items found to clean up");
                                return CompletableFuture.completedFuture(null);
                            }

                            logger.info("Found {} test items to clean up", keys.size());

                            // Track successful and failed deletions
                            final java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(
                                    0);
                            final java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(
                                    0);
                            final java.util.List<String> failedKeys = new java.util.concurrent.CopyOnWriteArrayList<>();

                            // Delete items with very conservative concurrency to avoid overwhelming
                            // DynamoDB
                            // After high-throughput writes, DynamoDB needs time to scale down
                            List<CompletableFuture<Void>> deleteFutures = new ArrayList<>();
                            Semaphore deleteSemaphore = new Semaphore(Math.min(5, keys.size())); // Very conservative
                                                                                                 // limit

                            for (String key : keys) {
                                CompletableFuture<Void> deleteFuture = CompletableFuture.runAsync(() -> {
                                    try {
                                        deleteSemaphore.acquire();
                                        try {
                                            // Add small delay between deletes to avoid overwhelming DynamoDB
                                            Thread.sleep(50); // 50ms delay between delete operations

                                            resilientDynamoDBService.getDynamoDBRepository()
                                                    .deleteItem(config.getTableName(), key)
                                                    .thenRun(() -> {
                                                        successCount.incrementAndGet();
                                                        logger.debug("Successfully deleted test item: {}", key);
                                                    })
                                                    .exceptionally(throwable -> {
                                                        failureCount.incrementAndGet();
                                                        failedKeys.add(key);
                                                        logger.warn("Failed to delete test item: {} - {}", key,
                                                                throwable.getMessage());
                                                        return null;
                                                    })
                                                    .join();
                                        } finally {
                                            deleteSemaphore.release();
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        failureCount.incrementAndGet();
                                        failedKeys.add(key);
                                        logger.error("Interrupted while deleting item: {}", key, e);
                                    }
                                }, virtualThreadExecutor);

                                deleteFutures.add(deleteFuture);
                            }

                            // Wait for all deletions to complete
                            return CompletableFuture.allOf(deleteFutures.toArray(new CompletableFuture[0]))
                                    .thenRun(() -> {
                                        int successful = successCount.get();
                                        int failed = failureCount.get();

                                        logger.info(
                                                "Cleanup completed. Successfully deleted: {}, Failed: {}, Total attempted: {}",
                                                successful, failed, keys.size());

                                        if (failed > 0) {
                                            logger.warn("Failed to delete {} items. Failed keys: {}",
                                                    failed, failedKeys.subList(0, Math.min(10, failedKeys.size())));

                                            if (failedKeys.size() > 10) {
                                                logger.warn("... and {} more failed deletions", failedKeys.size() - 10);
                                            }
                                        }

                                        // Verify cleanup by doing a final scan
                                        verifyCleanup(config.getTableName(), keyPrefix);
                                    });
                        })
                        .join();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Cleanup was interrupted", e);
                throw new RuntimeException("Test data cleanup was interrupted", e);
            } catch (Exception e) {
                logger.error("Error during test data cleanup", e);
                throw new RuntimeException("Test data cleanup failed", e);
            }
        }, virtualThreadExecutor);
    }

    /**
     * Verifies that cleanup was successful by performing a final scan.
     */
    private void verifyCleanup(String tableName, String keyPrefix) {
        logger.info("Verifying cleanup completion...");

        try {
            resilientDynamoDBService.getDynamoDBRepository()
                    .scanItemKeys(tableName, keyPrefix)
                    .thenAccept(remainingKeys -> {
                        if (remainingKeys.isEmpty()) {
                            logger.info("Cleanup verification successful: No test items remain in the table");
                        } else {
                            logger.warn("Cleanup verification found {} remaining test items in the table",
                                    remainingKeys.size());
                            logger.warn("Remaining keys (first 10): {}",
                                    remainingKeys.subList(0, Math.min(10, remainingKeys.size())));
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("Failed to verify cleanup", throwable);
                        return null;
                    })
                    .join();
        } catch (Exception e) {
            logger.error("Error during cleanup verification", e);
        }
    }

    /**
     * Performs enhanced duplicate accuracy validation using the
     * AccurateDuplicateCounter.
     * This provides more accurate duplicate error counting by analyzing the actual
     * table state.
     * 
     * @param config  the test configuration
     * @param summary the test summary containing reported metrics
     */
    private void performEnhancedDuplicateValidation(TestConfiguration config, TestSummary summary) {
        try {
            logger.info("=== PERFORMING ENHANCED DUPLICATE ACCURACY VALIDATION ===");

            // Calculate accurate duplicate errors using the enhanced counter
            int accurateDuplicateErrors = accurateDuplicateCounter.calculateAccurateDuplicateErrors(
                    config.getTableName(),
                    config.getTotalItems(),
                    config.getDuplicatePercentage());

            // Get reported duplicate errors from summary
            int reportedDuplicateErrors = summary.getErrorTypeCounts().getOrDefault("Duplicate Key", 0L).intValue();

            // Calculate accuracy percentage
            double accuracy = 100.0;
            if (accurateDuplicateErrors > 0) {
                accuracy = Math.min(100.0, (double) Math.min(reportedDuplicateErrors, accurateDuplicateErrors) /
                        Math.max(reportedDuplicateErrors, accurateDuplicateErrors) * 100.0);
            } else if (reportedDuplicateErrors > 0) {
                accuracy = 0.0;
            }

            logger.info("Enhanced duplicate accuracy validation completed:");
            logger.info("  Reported duplicate errors: {}", reportedDuplicateErrors);
            logger.info("  Accurate duplicate errors: {}", accurateDuplicateErrors);
            logger.info("  Accuracy: {:.2f}%", accuracy);

            // Log analysis
            if (accuracy >= 95.0) {
                logger.info("✅ EXCELLENT duplicate error accuracy (>= 95%)");
            } else if (accuracy >= 85.0) {
                logger.info("✅ GOOD duplicate error accuracy (>= 85%)");
            } else if (accuracy >= 70.0) {
                logger.warn("⚠️ ACCEPTABLE duplicate error accuracy (>= 70%)");
            } else {
                logger.error("❌ POOR duplicate error accuracy (< 70%) - possible race conditions");
            }

        } catch (Exception e) {
            logger.error("=== FAILED TO PERFORM ENHANCED DUPLICATE ACCURACY VALIDATION ===", e);
        }
    }
}