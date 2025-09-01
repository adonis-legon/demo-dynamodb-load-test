package com.example.dynamodb.loadtest.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Model representing a test item to be written to DynamoDB during load testing.
 * Contains primary key, payload data, timestamp, and additional attributes.
 */
public class TestItem {

    @NotBlank(message = "Primary key cannot be blank")
    @Size(max = 255, message = "Primary key must not exceed 255 characters")
    private String primaryKey;

    @NotBlank(message = "Payload cannot be blank")
    @Size(max = 400000, message = "Payload must not exceed 400KB (DynamoDB item size limit)")
    private String payload;

    @NotNull(message = "Timestamp cannot be null")
    private Instant timestamp;

    private Map<String, Object> attributes;

    // Default constructor
    public TestItem() {
        this.attributes = new HashMap<>();
        this.timestamp = Instant.now();
    }

    // Constructor with primary key and payload
    public TestItem(String primaryKey, String payload) {
        this();
        this.primaryKey = primaryKey;
        this.payload = payload;
    }

    // Full constructor
    public TestItem(String primaryKey, String payload, Instant timestamp, Map<String, Object> attributes) {
        this.primaryKey = primaryKey;
        this.payload = payload;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
    }

    // Getters and Setters
    public String getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
    }

    // Utility methods for attributes
    public void addAttribute(String key, Object value) {
        if (key != null && value != null) {
            this.attributes.put(key, value);
        }
    }

    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }

    public void removeAttribute(String key) {
        this.attributes.remove(key);
    }

    // Validation helper methods
    public boolean isValid() {
        return primaryKey != null && !primaryKey.trim().isEmpty() &&
                payload != null && !payload.trim().isEmpty() &&
                timestamp != null &&
                primaryKey.length() <= 255 &&
                payload.length() <= 400000; // 400KB limit
    }

    /**
     * Calculates the approximate size of this item in bytes for DynamoDB size
     * validation.
     * This is a rough estimate based on string lengths and attribute count.
     */
    public long getApproximateSize() {
        long size = 0;

        // Primary key size
        if (primaryKey != null) {
            size += primaryKey.getBytes().length;
        }

        // Payload size
        if (payload != null) {
            size += payload.getBytes().length;
        }

        // Timestamp size (ISO string representation)
        if (timestamp != null) {
            size += timestamp.toString().getBytes().length;
        }

        // Attributes size (rough estimate)
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            size += entry.getKey().getBytes().length;
            if (entry.getValue() != null) {
                size += entry.getValue().toString().getBytes().length;
            }
        }

        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TestItem testItem = (TestItem) o;
        return Objects.equals(primaryKey, testItem.primaryKey) &&
                Objects.equals(payload, testItem.payload) &&
                Objects.equals(timestamp, testItem.timestamp) &&
                Objects.equals(attributes, testItem.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primaryKey, payload, timestamp, attributes);
    }

    @Override
    public String toString() {
        return "TestItem{" +
                "primaryKey='" + primaryKey + '\'' +
                ", payload='" + (payload != null ? payload.substring(0, Math.min(50, payload.length())) + "..." : null)
                + '\'' +
                ", timestamp=" + timestamp +
                ", attributes=" + attributes +
                ", approximateSize=" + getApproximateSize() + " bytes" +
                '}';
    }
}