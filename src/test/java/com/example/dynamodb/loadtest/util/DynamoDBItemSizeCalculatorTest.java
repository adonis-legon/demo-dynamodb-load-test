package com.example.dynamodb.loadtest.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DynamoDBItemSizeCalculator.
 */
class DynamoDBItemSizeCalculatorTest {

    @Test
    void calculateItemSize_SimpleStringAttributes() {
        // Given
        Map<String, AttributeValue> itemMap = new HashMap<>();
        itemMap.put("pk", AttributeValue.builder().s("test-key").build());
        itemMap.put("payload", AttributeValue.builder().s("test-payload").build());

        // When
        int size = DynamoDBItemSizeCalculator.calculateItemSize(itemMap);

        // Then
        // "pk" (2) + "test-key" (8) + "payload" (7) + "test-payload" (12) = 29 bytes
        assertThat(size).isEqualTo(29);
    }

    @Test
    void calculateItemSize_MixedAttributeTypes() {
        // Given
        Map<String, AttributeValue> itemMap = new HashMap<>();
        itemMap.put("str", AttributeValue.builder().s("hello").build());
        itemMap.put("num", AttributeValue.builder().n("123").build());
        itemMap.put("bool", AttributeValue.builder().bool(true).build());

        // When
        int size = DynamoDBItemSizeCalculator.calculateItemSize(itemMap);

        // Then
        // "str" (3) + "hello" (5) + "num" (3) + "123" (3) + "bool" (4) + 1 (boolean) =
        // 19 bytes
        assertThat(size).isEqualTo(19);
    }

    @Test
    void calculateAttributeValueSize_StringValue() {
        // Given
        AttributeValue value = AttributeValue.builder().s("test").build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        assertThat(size).isEqualTo(4); // "test" = 4 bytes
    }

    @Test
    void calculateAttributeValueSize_NumberValue() {
        // Given
        AttributeValue value = AttributeValue.builder().n("12345").build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        assertThat(size).isEqualTo(5); // "12345" = 5 bytes
    }

    @Test
    void calculateAttributeValueSize_BooleanValue() {
        // Given
        AttributeValue value = AttributeValue.builder().bool(true).build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        assertThat(size).isEqualTo(1); // Boolean = 1 byte
    }

    @Test
    void calculateAttributeValueSize_NullValue() {
        // Given
        AttributeValue value = AttributeValue.builder().nul(true).build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        assertThat(size).isEqualTo(1); // Null = 1 byte
    }

    @Test
    void calculateAttributeValueSize_BinaryValue() {
        // Given
        byte[] data = "binary data".getBytes();
        AttributeValue value = AttributeValue.builder().b(SdkBytes.fromByteArray(data)).build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        assertThat(size).isEqualTo(data.length);
    }

    @Test
    void calculateAttributeValueSize_StringSetValue() {
        // Given
        AttributeValue value = AttributeValue.builder().ss("item1", "item2", "item3").build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        // "item1" (5) + "item2" (5) + "item3" (5) = 15 bytes
        assertThat(size).isEqualTo(15);
    }

    @Test
    void calculateAttributeValueSize_NumberSetValue() {
        // Given
        AttributeValue value = AttributeValue.builder().ns("1", "22", "333").build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        // "1" (1) + "22" (2) + "333" (3) = 6 bytes
        assertThat(size).isEqualTo(6);
    }

    @Test
    void calculateAttributeValueSize_ListValue() {
        // Given
        AttributeValue value = AttributeValue.builder().l(
                AttributeValue.builder().s("item1").build(),
                AttributeValue.builder().n("123").build()).build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        // "item1" (5) + "123" (3) = 8 bytes
        assertThat(size).isEqualTo(8);
    }

    @Test
    void calculateAttributeValueSize_MapValue() {
        // Given
        Map<String, AttributeValue> nestedMap = new HashMap<>();
        nestedMap.put("key1", AttributeValue.builder().s("value1").build());
        nestedMap.put("key2", AttributeValue.builder().n("42").build());

        AttributeValue value = AttributeValue.builder().m(nestedMap).build();

        // When
        int size = DynamoDBItemSizeCalculator.calculateAttributeValueSize(value);

        // Then
        // "key1" (4) + "value1" (6) + "key2" (4) + "42" (2) = 16 bytes
        assertThat(size).isEqualTo(16);
    }

    @Test
    void isWithinSizeLimit_WithinLimit() {
        // Given
        int itemSize = 400;

        // When
        boolean result = DynamoDBItemSizeCalculator.isWithinSizeLimit(itemSize);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void isWithinSizeLimit_ExceedsLimit() {
        // Given
        int itemSize = 600;

        // When
        boolean result = DynamoDBItemSizeCalculator.isWithinSizeLimit(itemSize);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void isWithinSizeLimit_ExactLimit() {
        // Given
        int itemSize = DynamoDBItemSizeCalculator.MAX_ITEM_SIZE_BYTES;

        // When
        boolean result = DynamoDBItemSizeCalculator.isWithinSizeLimit(itemSize);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void isWithinDynamoDBSizeLimit_WithinLimit() {
        // Given
        int itemSize = 1000;

        // When
        boolean result = DynamoDBItemSizeCalculator.isWithinDynamoDBSizeLimit(itemSize);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void constants() {
        // Verify the constants are set correctly
        assertThat(DynamoDBItemSizeCalculator.MAX_ITEM_SIZE_BYTES).isEqualTo(500);
        assertThat(DynamoDBItemSizeCalculator.DYNAMODB_MAX_ITEM_SIZE_BYTES).isEqualTo(400 * 1024);
    }
}