package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestItem;
import com.example.dynamodb.loadtest.repository.DynamoDBRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DynamoDB operations with LocalStack.
 */
class DynamoDBOperationsIntegrationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private DynamoDBRepository dynamoDBRepository;

    @AfterEach
    void cleanup() {
        cleanupTestData();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldPutItemSuccessfully() {
        // Given
        TestItem testItem = createTestItem("test-key-1", "test-payload");

        // When
        CompletableFuture<PutItemResponse> future = dynamoDBRepository.putItem(testItem);
        PutItemResponse response = future.join();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();

        // Verify item was stored
        GetItemResponse getResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s("test-key-1").build()))
                .build()).join();

        assertThat(getResponse.item()).isNotEmpty();
        assertThat(getResponse.item().get("payload").s()).isEqualTo("test-payload");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleMultipleConcurrentPuts() {
        // Given
        List<TestItem> testItems = new ArrayList<>();
        List<CompletableFuture<PutItemResponse>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            TestItem item = createTestItem("concurrent-key-" + i, "payload-" + i);
            testItems.add(item);
        }

        // When - Execute concurrent puts
        for (TestItem item : testItems) {
            CompletableFuture<PutItemResponse> future = dynamoDBRepository.putItem(item);
            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        allFutures.join();

        // Then - Verify all items were stored
        ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .build()).join();

        assertThat(scanResponse.items()).hasSize(10);

        // Verify all responses were successful
        for (CompletableFuture<PutItemResponse> future : futures) {
            PutItemResponse response = future.join();
            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleDuplicateKeys() {
        // Given
        TestItem item1 = createTestItem("duplicate-key", "payload-1");
        TestItem item2 = createTestItem("duplicate-key", "payload-2");

        // When - Put same key twice
        CompletableFuture<PutItemResponse> future1 = dynamoDBRepository.putItem(item1);
        PutItemResponse response1 = future1.join();

        CompletableFuture<PutItemResponse> future2 = dynamoDBRepository.putItem(item2);
        PutItemResponse response2 = future2.join();

        // Then - Both should succeed (second overwrites first)
        assertThat(response1.sdkHttpResponse().isSuccessful()).isTrue();
        assertThat(response2.sdkHttpResponse().isSuccessful()).isTrue();

        // Verify final state
        GetItemResponse getResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s("duplicate-key").build()))
                .build()).join();

        assertThat(getResponse.item().get("payload").s()).isEqualTo("payload-2");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleLargePayloads() {
        // Given - Create item with large payload (but under DynamoDB limit)
        StringBuilder largePayload = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largePayload.append("This is a test payload with some content. ");
        }

        TestItem testItem = createTestItem("large-payload-key", largePayload.toString());

        // When
        CompletableFuture<PutItemResponse> future = dynamoDBRepository.putItem(testItem);
        PutItemResponse response = future.join();

        // Then
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();

        // Verify item was stored correctly
        GetItemResponse getResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s("large-payload-key").build()))
                .build()).join();

        assertThat(getResponse.item().get("payload").s()).isEqualTo(largePayload.toString());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldVerifyTableExists() {
        // When
        CompletableFuture<Boolean> future = dynamoDBRepository.tableExists(TEST_TABLE_NAME);
        Boolean exists = future.join();

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldReturnFalseForNonExistentTable() {
        // When
        CompletableFuture<Boolean> future = dynamoDBRepository.tableExists("non-existent-table");
        Boolean exists = future.join();

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleItemsWithAttributes() {
        // Given
        TestItem testItem = createTestItem("attr-key", "payload");
        testItem.getAttributes().put("test-attribute", "test-value");
        testItem.getAttributes().put("numeric-attribute", 42);
        testItem.getAttributes().put("boolean-attribute", true);

        // When
        CompletableFuture<PutItemResponse> future = dynamoDBRepository.putItem(testItem);
        PutItemResponse response = future.join();

        // Then
        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();

        // Verify attributes were stored
        GetItemResponse getResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s("attr-key").build()))
                .build()).join();

        Map<String, AttributeValue> item = getResponse.item();
        assertThat(item.get("test-attribute").s()).isEqualTo("test-value");
        assertThat(item.get("numeric-attribute").n()).isEqualTo("42");
        assertThat(item.get("boolean-attribute").bool()).isTrue();
    }

    private TestItem createTestItem(String primaryKey, String payload) {
        TestItem item = new TestItem();
        item.setPrimaryKey(primaryKey);
        item.setPayload(payload);
        item.setTimestamp(Instant.now());
        return item;
    }
}