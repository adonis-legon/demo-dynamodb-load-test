package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.model.TestItem;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for executing DynamoDB load tests.
 * Provides methods for generating test items and executing load tests with
 * varying concurrency levels.
 */
public interface LoadTestService {

    /**
     * Executes a complete load test based on the provided configuration.
     * 
     * @param config the test configuration
     * @return CompletableFuture containing the test summary
     */
    CompletableFuture<TestSummary> executeLoadTest(TestConfiguration config);

    /**
     * Executes operations with a specific concurrency level.
     * 
     * @param items       the items to process
     * @param concurrency the concurrency level
     * @return CompletableFuture that completes when all operations are done
     */
    CompletableFuture<Void> executeWithConcurrency(List<TestItem> items, int concurrency);

    /**
     * Generates test items for the load test.
     * 
     * @param count               the number of items to generate
     * @param duplicatePercentage the percentage of items that should have duplicate
     *                            keys (0.0 = no duplicates)
     * @return list of generated test items
     */
    List<TestItem> generateTestItems(int count, double duplicatePercentage);

    /**
     * Generates a unique primary key for test items.
     * 
     * @return a unique primary key
     */
    String generateUniqueKey();

    /**
     * Generates a duplicate primary key based on existing keys.
     * 
     * @param existingKeys list of existing keys to choose from
     * @return a duplicate key, or a unique key if no existing keys available
     */
    String generateDuplicateKey(List<String> existingKeys);

    /**
     * Generates test payload data.
     * 
     * @param sizeBytes the approximate size of the payload in bytes
     * @return generated payload string
     */
    String generatePayload(int sizeBytes);

    /**
     * Cleans up test data from the DynamoDB table.
     * 
     * @param config the test configuration containing table name and other settings
     * @return CompletableFuture that completes when cleanup is done
     */
    CompletableFuture<Void> cleanupTestData(TestConfiguration config);
}