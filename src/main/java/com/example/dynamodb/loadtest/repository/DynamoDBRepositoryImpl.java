package com.example.dynamodb.loadtest.repository;

import com.example.dynamodb.loadtest.exception.DynamoDBAccessException;
import com.example.dynamodb.loadtest.exception.ItemSizeExceededException;
import com.example.dynamodb.loadtest.model.TestItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of DynamoDBRepository using AWS SDK v2.
 * Provides async operations for DynamoDB item operations and table management.
 */
@Repository
public class DynamoDBRepositoryImpl implements DynamoDBRepository {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDBRepositoryImpl.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final long MAX_ITEM_SIZE_BYTES = 500;

    private final DynamoDbAsyncClient dynamoDbClient;
    private final String tableName;

    public DynamoDBRepositoryImpl(DynamoDbAsyncClient dynamoDbClient,
            @Value("${TABLE_NAME:load-test-table}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public CompletableFuture<PutItemResponse> putItem(TestItem item) {
        logger.debug("Putting item with primary key: {}", item.getPrimaryKey());

        // Validate item size before processing
        long itemSize = item.getApproximateSize();
        if (itemSize > MAX_ITEM_SIZE_BYTES) {
            logger.error("Item size {} bytes exceeds maximum allowed size of {} bytes for item: {}",
                    itemSize, MAX_ITEM_SIZE_BYTES, item.getPrimaryKey());
            CompletableFuture<PutItemResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(
                    new ItemSizeExceededException(item.getPrimaryKey(), itemSize, MAX_ITEM_SIZE_BYTES));
            return failedFuture;
        }

        try {
            Map<String, AttributeValue> itemMap = convertToAttributeValueMap(item);

            // Use condition expression to prevent overwriting existing items
            // This will cause a ConditionalCheckFailedException if the item already exists
            PutItemRequest request = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(itemMap)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build();

            return dynamoDbClient.putItem(request)
                    .thenApply(response -> {
                        logger.debug("Successfully put item with primary key: {}", item.getPrimaryKey());
                        return response;
                    })
                    .exceptionally(throwable -> {
                        logger.error("Error putting item with primary key: {}", item.getPrimaryKey(), throwable);
                        throw new DynamoDBAccessException("Failed to put item: " + item.getPrimaryKey(), throwable);
                    });
        } catch (Exception e) {
            logger.error("Error converting item to DynamoDB format: {}", item.getPrimaryKey(), e);
            CompletableFuture<PutItemResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(
                    new DynamoDBAccessException("Failed to convert item: " + item.getPrimaryKey(), e));
            return failedFuture;
        }
    }

    @Override
    public CompletableFuture<Boolean> tableExists(String tableName) {
        logger.debug("Checking if table exists: {}", tableName);

        DescribeTableRequest request = DescribeTableRequest.builder()
                .tableName(tableName)
                .build();

        return dynamoDbClient.describeTable(request)
                .thenApply(response -> {
                    boolean exists = response.table() != null;
                    logger.debug("Table {} exists: {}", tableName, exists);
                    return exists;
                })
                .exceptionally(throwable -> {
                    if (throwable.getCause() instanceof ResourceNotFoundException) {
                        logger.debug("Table {} does not exist", tableName);
                        return false;
                    } else {
                        logger.error("Error checking if table exists: {}", tableName, throwable);
                        throw new DynamoDBAccessException("Failed to check table existence: " + tableName, throwable);
                    }
                });
    }

    @Override
    public CompletableFuture<java.util.List<String>> scanItemKeys(String tableName, String keyPrefix) {
        logger.debug("Scanning table {} for items with key prefix: {}", tableName, keyPrefix);

        return scanItemKeysRecursive(tableName, keyPrefix, null, new java.util.ArrayList<>());
    }

    /**
     * Recursively scans the table to handle pagination and collect all matching
     * keys.
     */
    private CompletableFuture<java.util.List<String>> scanItemKeysRecursive(String tableName, String keyPrefix,
            Map<String, AttributeValue> lastEvaluatedKey, java.util.List<String> accumulatedKeys) {

        ScanRequest.Builder requestBuilder = ScanRequest.builder()
                .tableName(tableName)
                .projectionExpression("pk");

        // Add filter expression if keyPrefix is provided
        if (keyPrefix != null && !keyPrefix.trim().isEmpty()) {
            requestBuilder.filterExpression("begins_with(pk, :prefix)")
                    .expressionAttributeValues(Map.of(
                            ":prefix", AttributeValue.builder().s(keyPrefix).build()));
        }

        // Handle pagination
        if (lastEvaluatedKey != null) {
            requestBuilder.exclusiveStartKey(lastEvaluatedKey);
        }

        ScanRequest request = requestBuilder.build();

        return dynamoDbClient.scan(request)
                .thenCompose(response -> {
                    // Extract keys from this page
                    java.util.List<String> pageKeys = response.items().stream()
                            .map(item -> item.get("pk"))
                            .filter(attr -> attr != null && attr.s() != null)
                            .map(attr -> attr.s())
                            .collect(java.util.stream.Collectors.toList());

                    // Add to accumulated results
                    accumulatedKeys.addAll(pageKeys);

                    logger.debug("Scanned page with {} items (total so far: {})", pageKeys.size(),
                            accumulatedKeys.size());

                    // Check if there are more pages
                    if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
                        // Recursively scan the next page
                        return scanItemKeysRecursive(tableName, keyPrefix, response.lastEvaluatedKey(),
                                accumulatedKeys);
                    } else {
                        // No more pages, return all accumulated keys
                        logger.info("Completed scanning table {}. Found {} items with key prefix '{}'",
                                tableName, accumulatedKeys.size(), keyPrefix);
                        return CompletableFuture.completedFuture(accumulatedKeys);
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error scanning table {} for keys with prefix: {}", tableName, keyPrefix, throwable);
                    throw new DynamoDBAccessException("Failed to scan table: " + tableName, throwable);
                });
    }

    @Override
    public CompletableFuture<DeleteItemResponse> deleteItem(String tableName, String primaryKey) {
        logger.debug("Deleting item with primary key: {} from table: {}", primaryKey, tableName);

        DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("pk", AttributeValue.builder().s(primaryKey).build()))
                .build();

        return dynamoDbClient.deleteItem(request)
                .thenApply(response -> {
                    logger.debug("Successfully deleted item with primary key: {}", primaryKey);
                    return response;
                })
                .exceptionally(throwable -> {
                    logger.error("Error deleting item with primary key: {}", primaryKey, throwable);
                    throw new DynamoDBAccessException("Failed to delete item: " + primaryKey, throwable);
                });
    }

    /**
     * Converts a TestItem to a DynamoDB AttributeValue map.
     */
    private Map<String, AttributeValue> convertToAttributeValueMap(TestItem item) {
        Map<String, AttributeValue> itemMap = new HashMap<>();

        // Primary key (partition key)
        itemMap.put("pk", AttributeValue.builder().s(item.getPrimaryKey()).build());

        // Payload
        if (item.getPayload() != null) {
            itemMap.put("payload", AttributeValue.builder().s(item.getPayload()).build());
        }

        // Timestamp
        if (item.getTimestamp() != null) {
            itemMap.put("timestamp", AttributeValue.builder().s(ISO_FORMATTER.format(item.getTimestamp())).build());
        }

        // Additional attributes
        Map<String, Object> attributes = item.getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (value != null && !key.equals("pk") && !key.equals("payload") && !key.equals("timestamp")) {
                    AttributeValue attributeValue = convertObjectToAttributeValue(value);
                    if (attributeValue != null) {
                        itemMap.put(key, attributeValue);
                    }
                }
            }
        }

        return itemMap;
    }

    /**
     * Converts a Java object to a DynamoDB AttributeValue.
     */
    private AttributeValue convertObjectToAttributeValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return AttributeValue.builder().s((String) value).build();
        } else if (value instanceof Number) {
            return AttributeValue.builder().n(value.toString()).build();
        } else if (value instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) value).build();
        } else {
            // For other types, convert to string
            return AttributeValue.builder().s(value.toString()).build();
        }
    }
}