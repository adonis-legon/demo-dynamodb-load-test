package com.example.dynamodb.loadtest.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ItemSizeExceededException.
 */
class ItemSizeExceededExceptionTest {

    @Test
    void constructor_WithSizes() {
        // Given
        int actualSize = 600;
        int maxSize = 500;

        // When
        ItemSizeExceededException exception = new ItemSizeExceededException(actualSize, maxSize);

        // Then
        assertThat(exception.getMessage())
                .isEqualTo("Item size 600 bytes exceeds maximum allowed size of 500 bytes");
        assertThat(exception.getActualSize()).isEqualTo(actualSize);
        assertThat(exception.getMaxSize()).isEqualTo(maxSize);
    }

    @Test
    void constructor_WithItemIdAndSizes() {
        // Given
        String itemId = "test-item-123";
        int actualSize = 750;
        int maxSize = 500;

        // When
        ItemSizeExceededException exception = new ItemSizeExceededException(itemId, actualSize, maxSize);

        // Then
        assertThat(exception.getMessage())
                .isEqualTo("Item 'test-item-123' size 750 bytes exceeds maximum allowed size of 500 bytes");
        assertThat(exception.getActualSize()).isEqualTo(actualSize);
        assertThat(exception.getMaxSize()).isEqualTo(maxSize);
    }

    @Test
    void getters() {
        // Given
        int actualSize = 1000;
        int maxSize = 500;
        ItemSizeExceededException exception = new ItemSizeExceededException(actualSize, maxSize);

        // When & Then
        assertThat(exception.getActualSize()).isEqualTo(actualSize);
        assertThat(exception.getMaxSize()).isEqualTo(maxSize);
    }
}