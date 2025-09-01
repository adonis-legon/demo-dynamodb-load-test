package com.example.dynamodb.loadtest.integration;

/**
 * Test suite for all LocalStack integration tests.
 * This allows running all integration tests together.
 * 
 * To run all integration tests, use:
 * mvn test -Dtest="*IntegrationTest"
 * 
 * Or run with the integration-tests profile:
 * mvn verify -P integration-tests
 */
public class IntegrationTestSuite {
    // This class serves as documentation for the integration test suite
    // Individual test classes contain the actual test methods:
    // - LoadTestEndToEndIntegrationTest
    // - ConfigurationLoadingIntegrationTest
    // - DynamoDBOperationsIntegrationTest
    // - MetricsCollectionIntegrationTest
    // - ApplicationLifecycleIntegrationTest
}