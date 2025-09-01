package com.example.dynamodb.loadtest.exception;

/**
 * Exception thrown when a DynamoDB item exceeds the maximum allowed size.
 */
public class ItemSizeExceededException extends RuntimeException {

    private final long actualSize;
    private final long maxSize;

    public ItemSizeExceededException(long actualSize, long maxSize) {
        super(String.format("Item size %d bytes exceeds maximum allowed size of %d bytes", actualSize, maxSize));
        this.actualSize = actualSize;
        this.maxSize = maxSize;
    }

    public ItemSizeExceededException(String itemId, long actualSize, long maxSize) {
        super(String.format("Item '%s' size %d bytes exceeds maximum allowed size of %d bytes", itemId, actualSize,
                maxSize));
        this.actualSize = actualSize;
        this.maxSize = maxSize;
    }

    public long getActualSize() {
        return actualSize;
    }

    public long getMaxSize() {
        return maxSize;
    }
}