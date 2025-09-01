package com.example.dynamodb.loadtest.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TestItem Model Tests")
class TestItemTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should create valid TestItem with all fields")
    void shouldCreateValidTestItemWithAllFields() {
        // Given
        String primaryKey = "test-key-123";
        String payload = "test payload data";
        Instant timestamp = Instant.now();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("testAttribute", "testValue");

        // When
        TestItem testItem = new TestItem(primaryKey, payload, timestamp, attributes);

        // Then
        assertEquals(primaryKey, testItem.getPrimaryKey());
        assertEquals(payload, testItem.getPayload());
        assertEquals(timestamp, testItem.getTimestamp());
        assertEquals(attributes, testItem.getAttributes());
        assertTrue(testItem.isValid());
    }

    @Test
    @DisplayName("Should create valid TestItem with minimal constructor")
    void shouldCreateValidTestItemWithMinimalConstructor() {
        // Given
        String primaryKey = "test-key-123";
        String payload = "test payload data";

        // When
        TestItem testItem = new TestItem(primaryKey, payload);

        // Then
        assertEquals(primaryKey, testItem.getPrimaryKey());
        assertEquals(payload, testItem.getPayload());
        assertNotNull(testItem.getTimestamp());
        assertNotNull(testItem.getAttributes());
        assertTrue(testItem.getAttributes().isEmpty());
        assertTrue(testItem.isValid());
    }

    @Test
    @DisplayName("Should create TestItem with default constructor")
    void shouldCreateTestItemWithDefaultConstructor() {
        // When
        TestItem testItem = new TestItem();

        // Then
        assertNull(testItem.getPrimaryKey());
        assertNull(testItem.getPayload());
        assertNotNull(testItem.getTimestamp());
        assertNotNull(testItem.getAttributes());
        assertTrue(testItem.getAttributes().isEmpty());
        assertFalse(testItem.isValid()); // Invalid because key and payload are null
    }

    @Test
    @DisplayName("Should validate primary key constraints")
    void shouldValidatePrimaryKeyConstraints() {
        // Test blank primary key
        TestItem testItem = new TestItem("", "payload");
        Set<ConstraintViolation<TestItem>> violations = validator.validate(testItem);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Primary key cannot be blank")));

        // Test null primary key
        testItem = new TestItem(null, "payload");
        violations = validator.validate(testItem);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Primary key cannot be blank")));

        // Test primary key too long
        String longKey = "a".repeat(256);
        testItem = new TestItem(longKey, "payload");
        violations = validator.validate(testItem);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Primary key must not exceed 255 characters")));
    }

    @Test
    @DisplayName("Should validate payload constraints")
    void shouldValidatePayloadConstraints() {
        // Test blank payload
        TestItem testItem = new TestItem("key", "");
        Set<ConstraintViolation<TestItem>> violations = validator.validate(testItem);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Payload cannot be blank")));

        // Test null payload
        testItem = new TestItem("key", null);
        violations = validator.validate(testItem);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Payload cannot be blank")));

        // Test payload too large (400KB limit)
        String largePayload = "a".repeat(400001);
        testItem = new TestItem("key", largePayload);
        violations = validator.validate(testItem);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Payload must not exceed 400KB")));
    }

    @Test
    @DisplayName("Should validate timestamp constraints")
    void shouldValidateTimestampConstraints() {
        // Test null timestamp by setting it directly
        TestItem testItem = new TestItem("key", "payload");
        testItem.setTimestamp(null);
        Set<ConstraintViolation<TestItem>> violations = validator.validate(testItem);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Timestamp cannot be null")));
    }

    @Test
    @DisplayName("Should manage attributes correctly")
    void shouldManageAttributesCorrectly() {
        // Given
        TestItem testItem = new TestItem("key", "payload");

        // When adding attributes
        testItem.addAttribute("attr1", "value1");
        testItem.addAttribute("attr2", 123);
        testItem.addAttribute("attr3", true);

        // Then
        assertEquals("value1", testItem.getAttribute("attr1"));
        assertEquals(123, testItem.getAttribute("attr2"));
        assertEquals(true, testItem.getAttribute("attr3"));
        assertEquals(3, testItem.getAttributes().size());

        // When removing attribute
        testItem.removeAttribute("attr2");

        // Then
        assertNull(testItem.getAttribute("attr2"));
        assertEquals(2, testItem.getAttributes().size());

        // Should not add null key or value
        testItem.addAttribute(null, "value");
        testItem.addAttribute("key", null);
        assertEquals(2, testItem.getAttributes().size());
    }

    @Test
    @DisplayName("Should calculate approximate size correctly")
    void shouldCalculateApproximateSizeCorrectly() {
        // Given
        String primaryKey = "test-key";
        String payload = "test-payload";
        TestItem testItem = new TestItem(primaryKey, payload);
        testItem.addAttribute("attr1", "value1");

        // When
        long size = testItem.getApproximateSize();

        // Then
        assertTrue(size > 0);
        assertTrue(size >= primaryKey.length() + payload.length());
    }

    @Test
    @DisplayName("Should handle null values in size calculation")
    void shouldHandleNullValuesInSizeCalculation() {
        // Given
        TestItem testItem = new TestItem();

        // When
        long size = testItem.getApproximateSize();

        // Then
        assertTrue(size >= 0);
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCodeCorrectly() {
        // Given
        Instant timestamp = Instant.now();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("attr", "value");

        TestItem item1 = new TestItem("key", "payload", timestamp, attributes);
        TestItem item2 = new TestItem("key", "payload", timestamp, attributes);
        TestItem item3 = new TestItem("different-key", "payload", timestamp, attributes);

        // Then
        assertEquals(item1, item2);
        assertEquals(item1.hashCode(), item2.hashCode());
        assertNotEquals(item1, item3);
        assertNotEquals(item1.hashCode(), item3.hashCode());
        assertNotEquals(item1, null);
        assertNotEquals(item1, "not a TestItem");
    }

    @Test
    @DisplayName("Should provide meaningful toString representation")
    void shouldProvideMeaningfulToStringRepresentation() {
        // Given
        TestItem testItem = new TestItem("test-key", "test-payload-data");
        testItem.addAttribute("attr1", "value1");

        // When
        String toString = testItem.toString();

        // Then
        assertTrue(toString.contains("TestItem"));
        assertTrue(toString.contains("test-key"));
        assertTrue(toString.contains("test-payload"));
        assertTrue(toString.contains("attr1"));
        assertTrue(toString.contains("approximateSize"));
    }

    @Test
    @DisplayName("Should truncate long payload in toString")
    void shouldTruncateLongPayloadInToString() {
        // Given
        String longPayload = "a".repeat(100);
        TestItem testItem = new TestItem("key", longPayload);

        // When
        String toString = testItem.toString();

        // Then
        assertTrue(toString.contains("..."));
        assertFalse(toString.contains(longPayload));
    }

    @Test
    @DisplayName("Should validate isValid method correctly")
    void shouldValidateIsValidMethodCorrectly() {
        // Valid item
        TestItem validItem = new TestItem("key", "payload");
        assertTrue(validItem.isValid());

        // Invalid - null primary key
        TestItem invalidItem1 = new TestItem(null, "payload");
        assertFalse(invalidItem1.isValid());

        // Invalid - empty primary key
        TestItem invalidItem2 = new TestItem("", "payload");
        assertFalse(invalidItem2.isValid());

        // Invalid - null payload
        TestItem invalidItem3 = new TestItem("key", null);
        assertFalse(invalidItem3.isValid());

        // Invalid - empty payload
        TestItem invalidItem4 = new TestItem("key", "");
        assertFalse(invalidItem4.isValid());

        // Invalid - primary key too long
        TestItem invalidItem5 = new TestItem("a".repeat(256), "payload");
        assertFalse(invalidItem5.isValid());

        // Invalid - payload too large
        TestItem invalidItem6 = new TestItem("key", "a".repeat(400001));
        assertFalse(invalidItem6.isValid());
    }

    @Test
    @DisplayName("Should handle attributes immutability correctly")
    void shouldHandleAttributesImmutabilityCorrectly() {
        // Given
        Map<String, Object> originalAttributes = new HashMap<>();
        originalAttributes.put("attr1", "value1");
        TestItem testItem = new TestItem("key", "payload", Instant.now(), originalAttributes);

        // When modifying the original map
        originalAttributes.put("attr2", "value2");

        // Then the TestItem should not be affected
        assertEquals(1, testItem.getAttributes().size());
        assertNull(testItem.getAttribute("attr2"));

        // When getting attributes and modifying the returned map
        Map<String, Object> retrievedAttributes = testItem.getAttributes();
        retrievedAttributes.put("attr3", "value3");

        // Then the TestItem should not be affected
        assertEquals(1, testItem.getAttributes().size());
        assertNull(testItem.getAttribute("attr3"));
    }
}