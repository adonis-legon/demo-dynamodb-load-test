package com.example.dynamodb.loadtest.repository;

import com.example.dynamodb.loadtest.exception.DynamoDBAccessException;
import com.example.dynamodb.loadtest.exception.ItemSizeExceededException;
import com.example.dynamodb.loadtest.model.TestItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamoDBRepositoryImplTest {

        @Mock
        private DynamoDbAsyncClient dynamoDbClient;

        private DynamoDBRepositoryImpl repository;
        private final String tableName = "test-table";

        @BeforeEach
        void setUp() {
                repository = new DynamoDBRepositoryImpl(dynamoDbClient, tableName);
        }

        @Test
        void putItem_Success() {
                // Given
                TestItem item = new TestItem("test-key", "test-payload");
                item.setTimestamp(Instant.parse("2023-01-01T00:00:00Z"));
                item.addAttribute("testAttr", "testValue");
                item.addAttribute("numAttr", 42);
                item.addAttribute("boolAttr", true);

                PutItemResponse expectedResponse = PutItemResponse.builder().build();

                when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                                .thenReturn(CompletableFuture.completedFuture(expectedResponse));

                // When
                PutItemResponse result = repository.putItem(item).join();

                // Then
                assertThat(result).isSameAs(expectedResponse);

                ArgumentCaptor<PutItemRequest> requestCaptor = ArgumentCaptor.forClass(PutItemRequest.class);
                verify(dynamoDbClient).putItem(requestCaptor.capture());

                PutItemRequest capturedRequest = requestCaptor.getValue();
                assertThat(capturedRequest.tableName()).isEqualTo(tableName);

                Map<String, AttributeValue> itemMap = capturedRequest.item();
                assertThat(itemMap).containsKey("pk");
                assertThat(itemMap.get("pk").s()).isEqualTo("test-key");
                assertThat(itemMap).containsKey("payload");
                assertThat(itemMap.get("payload").s()).isEqualTo("test-payload");
                assertThat(itemMap).containsKey("timestamp");
                assertThat(itemMap.get("timestamp").s()).isEqualTo("2023-01-01T00:00:00Z");
                assertThat(itemMap).containsKey("testAttr");
                assertThat(itemMap.get("testAttr").s()).isEqualTo("testValue");
                assertThat(itemMap).containsKey("numAttr");
                assertThat(itemMap.get("numAttr").n()).isEqualTo("42");
                assertThat(itemMap).containsKey("boolAttr");
                assertThat(itemMap.get("boolAttr").bool()).isTrue();
        }

        @Test
        void putItem_DynamoDBError() {
                // Given
                TestItem item = new TestItem("test-key", "test-payload");

                CompletableFuture<PutItemResponse> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(
                                ProvisionedThroughputExceededException.builder()
                                                .message("Throughput exceeded")
                                                .build());

                when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                                .thenReturn(failedFuture);

                // When & Then
                assertThatThrownBy(() -> repository.putItem(item).join())
                                .isInstanceOf(CompletionException.class)
                                .hasCauseInstanceOf(DynamoDBAccessException.class)
                                .hasMessageContaining("Failed to put item: test-key");
        }

        @Test
        void tableExists_Success_TableExists() {
                // Given
                String testTableName = "existing-table";

                TableDescription tableDescription = TableDescription.builder()
                                .tableName(testTableName)
                                .build();

                DescribeTableResponse response = DescribeTableResponse.builder()
                                .table(tableDescription)
                                .build();

                when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                                .thenReturn(CompletableFuture.completedFuture(response));

                // When
                Boolean result = repository.tableExists(testTableName).join();

                // Then
                assertThat(result).isTrue();

                verify(dynamoDbClient).describeTable(
                                argThat((DescribeTableRequest request) -> request.tableName().equals(testTableName)));
        }

        @Test
        void tableExists_TableNotFound() {
                // Given
                String testTableName = "nonexistent-table";

                CompletableFuture<DescribeTableResponse> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(
                                ResourceNotFoundException.builder()
                                                .message("Table not found")
                                                .build());

                when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                                .thenReturn(failedFuture);

                // When
                Boolean result = repository.tableExists(testTableName).join();

                // Then
                assertThat(result).isFalse();
        }

        @Test
        void tableExists_DynamoDBError() {
                // Given
                String testTableName = "error-table";

                CompletableFuture<DescribeTableResponse> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(
                                DynamoDbException.builder()
                                                .message("Access denied")
                                                .build());

                when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                                .thenReturn(failedFuture);

                // When & Then
                assertThatThrownBy(() -> repository.tableExists(testTableName).join())
                                .isInstanceOf(CompletionException.class)
                                .hasCauseInstanceOf(DynamoDBAccessException.class)
                                .hasMessageContaining("Failed to check table existence: " + testTableName);
        }

        @Test
        void putItem_ItemSizeExceeded() {
                // Given - Create an item with a large payload that exceeds 500 bytes
                TestItem item = new TestItem("test-key", "x".repeat(500)); // 500 character payload

                // When & Then
                assertThatThrownBy(() -> repository.putItem(item).join())
                                .isInstanceOf(CompletionException.class)
                                .hasCauseInstanceOf(ItemSizeExceededException.class)
                                .hasMessageContaining("exceeds maximum allowed size of 500 bytes");

                // Verify that putItem was never called on the client
                verify(dynamoDbClient, never()).putItem(any(PutItemRequest.class));
        }

        @Test
        void putItem_ItemSizeWithinLimit() {
                // Given - Create an item with a small payload that's within the 500 byte limit
                TestItem item = new TestItem("key", "small-payload");
                PutItemResponse expectedResponse = PutItemResponse.builder().build();
                when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                                .thenReturn(CompletableFuture.completedFuture(expectedResponse));

                // When
                PutItemResponse result = repository.putItem(item).join();

                // Then
                assertThat(result).isSameAs(expectedResponse);
                verify(dynamoDbClient).putItem(any(PutItemRequest.class));
        }
}