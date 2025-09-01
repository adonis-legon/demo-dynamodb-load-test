package com.example.dynamodb.loadtest.repository;

import com.example.dynamodb.loadtest.exception.DynamoDBAccessException;
import com.example.dynamodb.loadtest.exception.ItemSizeExceededException;
import com.example.dynamodb.loadtest.model.TestItem;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Repository interface for DynamoDB operations.
 * Provides async methods for item operations and table management.
 */
public interface DynamoDBRepository {

    /**
     * Puts an item into the DynamoDB table.
     * 
     * @param item the TestItem to put into the table
     * @return CompletableFuture containing the PutItemResponse
     * @throws DynamoDBAccessException   if there's an error accessing DynamoDB
     * @throws ItemSizeExceededException if the item exceeds the maximum size limit
     */
    CompletableFuture<PutItemResponse> putItem(TestItem item);

    /**
     * Checks if the specified table exists.
     * 
     * @param tableName the name of the table to check
     * @return CompletableFuture containing true if table exists, false otherwise
     * @throws DynamoDBAccessException if there's an error accessing DynamoDB
     */
    CompletableFuture<Boolean> tableExists(String tableName);

    /**
     * Scans the table and returns all items with primary keys starting with the
     * specified prefix.
     * 
     * @param tableName the name of the table to scan
     * @param keyPrefix the prefix to filter primary keys (e.g., "test-item-")
     * @return CompletableFuture containing a list of primary keys
     * @throws DynamoDBAccessException if there's an error accessing DynamoDB
     */
    CompletableFuture<java.util.List<String>> scanItemKeys(String tableName, String keyPrefix);

    /**
     * Deletes an item from the DynamoDB table by primary key.
     * 
     * @param tableName  the name of the table
     * @param primaryKey the primary key of the item to delete
     * @return CompletableFuture containing the DeleteItemResponse
     * @throws DynamoDBAccessException if there's an error accessing DynamoDB
     */
    CompletableFuture<software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse> deleteItem(String tableName,
            String primaryKey);
}