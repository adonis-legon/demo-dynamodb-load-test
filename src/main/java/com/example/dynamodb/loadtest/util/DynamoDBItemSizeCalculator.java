package com.example.dynamodb.loadtest.util;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Utility class for calculating DynamoDB item sizes.
 * Provides methods to estimate the size of DynamoDB items in bytes.
 */
public class DynamoDBItemSizeCalculator {

    /**
     * Maximum item size allowed by this application (500 bytes).
     */
    public static final int MAX_ITEM_SIZE_BYTES = 500;

    /**
     * DynamoDB's actual maximum item size limit (400 KB).
     */
    public static final int DYNAMODB_MAX_ITEM_SIZE_BYTES = 400 * 1024;

    private DynamoDBItemSizeCalculator() {
        // Utility class - prevent instantiation
    }

    /**
     * Calculates the approximate size of a DynamoDB item in bytes.
     * This includes attribute names and values.
     * 
     * @param itemMap the DynamoDB item as a map of attribute names to values
     * @return the estimated size in bytes
     */
    public static int calculateItemSize(Map<String, AttributeValue> itemMap) {
        int totalSize = 0;
        for (Map.Entry<String, AttributeValue> entry : itemMap.entrySet()) {
            String attributeName = entry.getKey();
            AttributeValue attributeValue = entry.getValue();

            // Add attribute name size
            totalSize += attributeName.getBytes(StandardCharsets.UTF_8).length;

            // Add attribute value size
            totalSize += calculateAttributeValueSize(attributeValue);
        }
        return totalSize;
    }

    /**
     * Calculates the size of a single AttributeValue in bytes.
     * 
     * @param attributeValue the attribute value to measure
     * @return the estimated size in bytes
     */
    public static int calculateAttributeValueSize(AttributeValue attributeValue) {
        // Check string value
        if (attributeValue.s() != null) {
            return attributeValue.s().getBytes(StandardCharsets.UTF_8).length;
        }

        // Check number value
        if (attributeValue.n() != null) {
            return attributeValue.n().getBytes(StandardCharsets.UTF_8).length;
        }

        // Check boolean value
        if (attributeValue.bool() != null) {
            return 1; // Boolean values are typically 1 byte
        }

        // Check null value
        if (attributeValue.nul() != null && attributeValue.nul()) {
            return 1; // Null values are typically 1 byte
        }

        // Check binary value
        if (attributeValue.b() != null) {
            return attributeValue.b().asByteArray().length;
        }

        // Check string set
        if (attributeValue.ss() != null && !attributeValue.ss().isEmpty()) {
            return attributeValue.ss().stream()
                    .mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
        }

        // Check number set
        if (attributeValue.ns() != null && !attributeValue.ns().isEmpty()) {
            return attributeValue.ns().stream()
                    .mapToInt(n -> n.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
        }

        // Check binary set
        if (attributeValue.bs() != null && !attributeValue.bs().isEmpty()) {
            return attributeValue.bs().stream()
                    .mapToInt(b -> b.asByteArray().length)
                    .sum();
        }

        // Check map
        if (attributeValue.m() != null && !attributeValue.m().isEmpty()) {
            return calculateItemSize(attributeValue.m());
        }

        // Check list
        if (attributeValue.l() != null && !attributeValue.l().isEmpty()) {
            return attributeValue.l().stream()
                    .mapToInt(DynamoDBItemSizeCalculator::calculateAttributeValueSize)
                    .sum();
        }

        // For other types, return a conservative estimate
        return 8;
    }

    /**
     * Checks if an item size is within the application's size limit.
     * 
     * @param itemSize the size of the item in bytes
     * @return true if the item is within the size limit, false otherwise
     */
    public static boolean isWithinSizeLimit(int itemSize) {
        return itemSize <= MAX_ITEM_SIZE_BYTES;
    }

    /**
     * Checks if an item size is within DynamoDB's actual size limit.
     * 
     * @param itemSize the size of the item in bytes
     * @return true if the item is within DynamoDB's size limit, false otherwise
     */
    public static boolean isWithinDynamoDBSizeLimit(int itemSize) {
        return itemSize <= DYNAMODB_MAX_ITEM_SIZE_BYTES;
    }
}