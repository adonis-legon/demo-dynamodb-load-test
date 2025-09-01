package com.example.dynamodb.loadtest.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TestMetrics Model Tests")
class TestMetricsTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should create valid TestMetrics with default constructor")
    void shouldCreateValidTestMetricsWithDefaultConstructor() {
        // When
        TestMetrics metrics = new TestMetrics();

        // Then
        assertNotNull(metrics.getResponseTime());
        assertEquals(Duration.ZERO, metrics.getResponseTime());
        assertEquals(0, metrics.getSuccessCount());
        assertEquals(0, metrics.getErrorCount());
        assertNotNull(metrics.getErrorTypes());
        assertTrue(metrics.getErrorTypes().isEmpty());
        assertEquals(1, metrics.getConcurrencyLevel());
        assertNotNull(metrics.getTimestamp());
        assertTrue(metrics.isValid());
    }

    @Test
    @DisplayName("Should create valid TestMetrics with basic constructor")
    void shouldCreateValidTestMetricsWithBasicConstructor() {
        // Given
        Duration responseTime = Duration.ofMillis(500);
        int successCount = 100;
        int errorCount = 5;
        int concurrencyLevel = 10;

        // When
        TestMetrics metrics = new TestMetrics(responseTime, successCount, errorCount, concurrencyLevel);

        // Then
        assertEquals(responseTime, metrics.getResponseTime());
        assertEquals(successCount, metrics.getSuccessCount());
        assertEquals(errorCount, metrics.getErrorCount());
        assertEquals(concurrencyLevel, metrics.getConcurrencyLevel());
        assertNotNull(metrics.getErrorTypes());
        assertTrue(metrics.getErrorTypes().isEmpty());
        assertNotNull(metrics.getTimestamp());
        assertTrue(metrics.isValid());
    }

    @Test
    @DisplayName("Should create valid TestMetrics with full constructor")
    void shouldCreateValidTestMetricsWithFullConstructor() {
        // Given
        Duration responseTime = Duration.ofMillis(1000);
        Integer successCount = 200;
        Integer errorCount = 10;
        Map<String, Integer> errorTypes = new HashMap<>();
        errorTypes.put(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, 5);
        errorTypes.put(TestMetrics.ERROR_TYPE_DUPLICATE_KEY, 3);
        Integer concurrencyLevel = 20;
        Instant timestamp = Instant.now();

        // When
        TestMetrics metrics = new TestMetrics(responseTime, successCount, errorCount, errorTypes, concurrencyLevel,
                timestamp);

        // Then
        assertEquals(responseTime, metrics.getResponseTime());
        assertEquals(successCount, metrics.getSuccessCount());
        assertEquals(errorCount, metrics.getErrorCount());
        assertEquals(errorTypes, metrics.getErrorTypes());
        assertEquals(concurrencyLevel, metrics.getConcurrencyLevel());
        assertEquals(timestamp, metrics.getTimestamp());
        assertTrue(metrics.isValid());
    }

    @Test
    @DisplayName("Should validate response time constraints")
    void shouldValidateResponseTimeConstraints() {
        TestMetrics metrics = new TestMetrics();

        // Test null response time
        metrics.setResponseTime(null);
        Set<ConstraintViolation<TestMetrics>> violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Response time cannot be null")));
    }

    @Test
    @DisplayName("Should validate success count constraints")
    void shouldValidateSuccessCountConstraints() {
        TestMetrics metrics = new TestMetrics();

        // Test null success count
        metrics.setSuccessCount(null);
        Set<ConstraintViolation<TestMetrics>> violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Success count cannot be null")));

        // Test negative success count
        metrics.setSuccessCount(-1);
        violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Success count cannot be negative")));
    }

    @Test
    @DisplayName("Should validate error count constraints")
    void shouldValidateErrorCountConstraints() {
        TestMetrics metrics = new TestMetrics();

        // Test null error count
        metrics.setErrorCount(null);
        Set<ConstraintViolation<TestMetrics>> violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Error count cannot be null")));

        // Test negative error count
        metrics.setErrorCount(-1);
        violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Error count cannot be negative")));
    }

    @Test
    @DisplayName("Should validate concurrency level constraints")
    void shouldValidateConcurrencyLevelConstraints() {
        TestMetrics metrics = new TestMetrics();

        // Test null concurrency level
        metrics.setConcurrencyLevel(null);
        Set<ConstraintViolation<TestMetrics>> violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Concurrency level cannot be null")));

        // Test concurrency level too low
        metrics.setConcurrencyLevel(0);
        violations = validator.validate(metrics);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Concurrency level must be at least 1")));
    }

    @Test
    @DisplayName("Should calculate total operations correctly")
    void shouldCalculateTotalOperationsCorrectly() {
        TestMetrics metrics = new TestMetrics();
        metrics.setSuccessCount(100);
        metrics.setErrorCount(25);

        assertEquals(125, metrics.getTotalOperations());
    }

    @Test
    @DisplayName("Should calculate success rate correctly")
    void shouldCalculateSuccessRateCorrectly() {
        TestMetrics metrics = new TestMetrics();
        metrics.setSuccessCount(80);
        metrics.setErrorCount(20);

        assertEquals(80.0, metrics.getSuccessRate(), 0.01);

        // Test with zero operations
        metrics.setSuccessCount(0);
        metrics.setErrorCount(0);
        assertEquals(0.0, metrics.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("Should calculate error rate correctly")
    void shouldCalculateErrorRateCorrectly() {
        TestMetrics metrics = new TestMetrics();
        metrics.setSuccessCount(75);
        metrics.setErrorCount(25);

        assertEquals(25.0, metrics.getErrorRate(), 0.01);

        // Test with zero operations
        metrics.setSuccessCount(0);
        metrics.setErrorCount(0);
        assertEquals(0.0, metrics.getErrorRate(), 0.01);
    }

    @Test

    @DisplayName("Should calculate average response time correctly")
    void shouldCalculateAverageResponseTimeCorrectly() {
        TestMetrics metrics = new TestMetrics();
        metrics.setResponseTime(Duration.ofMillis(1000));
        metrics.setSuccessCount(8);
        metrics.setErrorCount(2);

        assertEquals(100.0, metrics.getAverageResponseTimeMs(), 0.01);

        // Test with zero operations
        metrics.setSuccessCount(0);
        metrics.setErrorCount(0);
        assertEquals(0.0, metrics.getAverageResponseTimeMs(), 0.01);
    }

    @Test
    @DisplayName("Should calculate throughput correctly")
    void shouldCalculateThroughputCorrectly() {
        TestMetrics metrics = new TestMetrics();
        metrics.setResponseTime(Duration.ofSeconds(10));
        metrics.setSuccessCount(90);
        metrics.setErrorCount(10);

        assertEquals(10.0, metrics.getThroughputPerSecond(), 0.01);

        // Test with zero response time
        metrics.setResponseTime(Duration.ZERO);
        assertEquals(0.0, metrics.getThroughputPerSecond(), 0.01);
    }

    @Test
    @DisplayName("Should manage errors correctly")
    void shouldManageErrorsCorrectly() {
        TestMetrics metrics = new TestMetrics();

        // Add single errors
        metrics.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);
        metrics.addError(TestMetrics.ERROR_TYPE_DUPLICATE_KEY);
        metrics.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);

        assertEquals(3, metrics.getErrorCount());
        assertEquals(2, metrics.getCapacityExceededErrors());
        assertEquals(1, metrics.getDuplicateKeyErrors());
        assertEquals(0, metrics.getThrottlingErrors());

        // Add multiple errors
        metrics.addErrors(TestMetrics.ERROR_TYPE_THROTTLING, 5);
        assertEquals(8, metrics.getErrorCount());
        assertEquals(5, metrics.getThrottlingErrors());

        // Test null and empty error types
        metrics.addError(null);
        metrics.addError("");
        metrics.addError("   ");
        assertEquals(8, metrics.getErrorCount()); // Should not change

        // Test adding zero or negative count
        metrics.addErrors(TestMetrics.ERROR_TYPE_NETWORK, 0);
        metrics.addErrors(TestMetrics.ERROR_TYPE_NETWORK, -1);
        assertEquals(8, metrics.getErrorCount()); // Should not change
    }

    @Test
    @DisplayName("Should manage successes correctly")
    void shouldManageSuccessesCorrectly() {
        TestMetrics metrics = new TestMetrics();

        metrics.addSuccess();
        assertEquals(1, metrics.getSuccessCount());

        metrics.addSuccesses(10);
        assertEquals(11, metrics.getSuccessCount());

        // Test adding zero or negative count
        metrics.addSuccesses(0);
        metrics.addSuccesses(-1);
        assertEquals(11, metrics.getSuccessCount()); // Should not change
    }

    @Test
    @DisplayName("Should merge metrics correctly")
    void shouldMergeMetricsCorrectly() {
        // Given
        TestMetrics metrics1 = new TestMetrics();
        metrics1.setSuccessCount(50);
        metrics1.setErrorCount(10);
        metrics1.setResponseTime(Duration.ofMillis(500));
        metrics1.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);
        metrics1.addError(TestMetrics.ERROR_TYPE_DUPLICATE_KEY);

        TestMetrics metrics2 = new TestMetrics();
        metrics2.setSuccessCount(30);
        metrics2.setErrorCount(5);
        metrics2.setResponseTime(Duration.ofMillis(300));
        metrics2.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);
        metrics2.addError(TestMetrics.ERROR_TYPE_THROTTLING);

        // When
        metrics1.merge(metrics2);

        // Then
        assertEquals(80, metrics1.getSuccessCount());
        assertEquals(19, metrics1.getErrorCount()); // 10 + 5 + 2 from metrics1 addError calls + 2 from metrics2
                                                    // addError calls
        assertEquals(Duration.ofMillis(800), metrics1.getResponseTime());
        assertEquals(2, metrics1.getCapacityExceededErrors());
        assertEquals(1, metrics1.getDuplicateKeyErrors());
        assertEquals(1, metrics1.getThrottlingErrors());

        // Test merging null
        metrics1.merge(null);
        assertEquals(80, metrics1.getSuccessCount()); // Should not change
    }

    @Test
    @DisplayName("Should create snapshot correctly")
    void shouldCreateSnapshotCorrectly() {
        // Given
        TestMetrics original = new TestMetrics();
        original.setSuccessCount(100);
        original.setErrorCount(20);
        original.setResponseTime(Duration.ofMillis(1000));
        original.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);

        // When
        TestMetrics snapshot = original.createSnapshot();

        // Then
        assertEquals(original, snapshot);
        assertNotSame(original, snapshot);

        // Modifying the snapshot should not affect the original
        snapshot.addSuccess();
        assertNotEquals(original.getSuccessCount(), snapshot.getSuccessCount());
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void shouldResetMetricsCorrectly() {
        // Given
        TestMetrics metrics = new TestMetrics();
        metrics.setSuccessCount(100);
        metrics.setErrorCount(20);
        metrics.setResponseTime(Duration.ofMillis(1000));
        metrics.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);

        // When
        metrics.reset();

        // Then
        assertEquals(0, metrics.getSuccessCount());
        assertEquals(0, metrics.getErrorCount());
        assertEquals(Duration.ZERO, metrics.getResponseTime());
        assertTrue(metrics.getErrorTypes().isEmpty());
        assertNotNull(metrics.getTimestamp());
    }

    @Test
    @DisplayName("Should validate isValid method correctly")
    void shouldValidateIsValidMethodCorrectly() {
        // Valid metrics
        TestMetrics validMetrics = new TestMetrics();
        assertTrue(validMetrics.isValid());

        // Invalid - null response time
        TestMetrics invalidMetrics1 = new TestMetrics();
        invalidMetrics1.setResponseTime(null);
        assertFalse(invalidMetrics1.isValid());

        // Invalid - negative response time
        TestMetrics invalidMetrics2 = new TestMetrics();
        invalidMetrics2.setResponseTime(Duration.ofMillis(-100));
        assertFalse(invalidMetrics2.isValid());

        // Invalid - null success count
        TestMetrics invalidMetrics3 = new TestMetrics();
        invalidMetrics3.setSuccessCount(null);
        assertFalse(invalidMetrics3.isValid());

        // Invalid - negative success count
        TestMetrics invalidMetrics4 = new TestMetrics();
        invalidMetrics4.setSuccessCount(-1);
        assertFalse(invalidMetrics4.isValid());

        // Invalid - concurrency level too low
        TestMetrics invalidMetrics5 = new TestMetrics();
        invalidMetrics5.setConcurrencyLevel(0);
        assertFalse(invalidMetrics5.isValid());
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCodeCorrectly() {
        // Given
        Duration responseTime = Duration.ofMillis(500);
        Instant timestamp = Instant.now();
        Map<String, Integer> errorTypes = new HashMap<>();
        errorTypes.put(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, 2);

        TestMetrics metrics1 = new TestMetrics(responseTime, 100, 10, errorTypes, 5, timestamp);
        TestMetrics metrics2 = new TestMetrics(responseTime, 100, 10, errorTypes, 5, timestamp);
        TestMetrics metrics3 = new TestMetrics(responseTime, 200, 10, errorTypes, 5, timestamp);

        // Then
        assertEquals(metrics1, metrics2);
        assertEquals(metrics1.hashCode(), metrics2.hashCode());
        assertNotEquals(metrics1, metrics3);
        assertNotEquals(metrics1.hashCode(), metrics3.hashCode());
        assertNotEquals(metrics1, null);
        assertNotEquals(metrics1, "not a TestMetrics");
    }

    @Test
    @DisplayName("Should provide meaningful toString representation")
    void shouldProvideMeaningfulToStringRepresentation() {
        // Given
        TestMetrics metrics = new TestMetrics();
        metrics.setSuccessCount(100);
        metrics.setErrorCount(20);
        metrics.setResponseTime(Duration.ofMillis(1000));
        metrics.setConcurrencyLevel(10);
        metrics.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);

        // When
        String toString = metrics.toString();

        // Then
        assertTrue(toString.contains("TestMetrics"));
        assertTrue(toString.contains("successCount=100"));
        assertTrue(toString.contains("errorCount=21")); // 20 + 1 from addError call
        assertTrue(toString.contains("concurrencyLevel=10"));
        assertTrue(toString.contains("totalOperations"));
        assertTrue(toString.contains("successRate"));
        assertTrue(toString.contains("errorRate"));
        assertTrue(toString.contains("avgResponseTimeMs"));
        assertTrue(toString.contains("throughputPerSec"));
    }

    @Test
    @DisplayName("Should handle error types immutability correctly")
    void shouldHandleErrorTypesImmutabilityCorrectly() {
        // Given
        Map<String, Integer> originalErrorTypes = new HashMap<>();
        originalErrorTypes.put(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, 5);
        TestMetrics metrics = new TestMetrics(Duration.ofMillis(500), 100, 10, originalErrorTypes, 5, Instant.now());

        // When modifying the original map
        originalErrorTypes.put(TestMetrics.ERROR_TYPE_DUPLICATE_KEY, 3);

        // Then the TestMetrics should not be affected
        assertEquals(1, metrics.getErrorTypes().size());
        assertEquals(0, metrics.getDuplicateKeyErrors());

        // When getting error types and modifying the returned map
        Map<String, Integer> retrievedErrorTypes = metrics.getErrorTypes();
        retrievedErrorTypes.put(TestMetrics.ERROR_TYPE_THROTTLING, 2);

        // Then the TestMetrics should not be affected
        assertEquals(1, metrics.getErrorTypes().size());
        assertEquals(0, metrics.getThrottlingErrors());
    }

    @Test
    @DisplayName("Should handle null values in constructors correctly")
    void shouldHandleNullValuesInConstructorsCorrectly() {
        // Test full constructor with null values
        TestMetrics metrics = new TestMetrics(null, null, null, null, null, null);

        assertEquals(Duration.ZERO, metrics.getResponseTime());
        assertEquals(0, metrics.getSuccessCount());
        assertEquals(0, metrics.getErrorCount());
        assertNotNull(metrics.getErrorTypes());
        assertTrue(metrics.getErrorTypes().isEmpty());
        assertEquals(1, metrics.getConcurrencyLevel());
        assertNotNull(metrics.getTimestamp());
    }
}