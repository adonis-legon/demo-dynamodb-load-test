package com.example.dynamodb.loadtest.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model representing performance metrics collected during DynamoDB load
 * testing.
 * Contains response times, error counts, and categorized error information.
 */
public class TestMetrics {

    @NotNull(message = "Response time cannot be null")
    private Duration responseTime;

    @NotNull(message = "Success count cannot be null")
    @Min(value = 0, message = "Success count cannot be negative")
    private Integer successCount;

    @NotNull(message = "Error count cannot be null")
    @Min(value = 0, message = "Error count cannot be negative")
    private Integer errorCount;

    @NotNull(message = "Error types map cannot be null")
    private Map<String, Integer> errorTypes;

    @NotNull(message = "Concurrency level cannot be null")
    @Min(value = 1, message = "Concurrency level must be at least 1")
    private Integer concurrencyLevel;

    @NotNull(message = "Timestamp cannot be null")
    private Instant timestamp;

    // Error type constants
    public static final String ERROR_TYPE_CAPACITY_EXCEEDED = "CapacityExceeded";
    public static final String ERROR_TYPE_DUPLICATE_KEY = "DuplicateKey";
    public static final String ERROR_TYPE_THROTTLING = "Throttling";
    public static final String ERROR_TYPE_NETWORK = "Network";
    public static final String ERROR_TYPE_TIMEOUT = "Timeout";
    public static final String ERROR_TYPE_VALIDATION = "Validation";
    public static final String ERROR_TYPE_UNKNOWN = "Unknown";

    // Default constructor
    public TestMetrics() {
        this.errorTypes = new ConcurrentHashMap<>();
        this.timestamp = Instant.now();
        this.successCount = 0;
        this.errorCount = 0;
        this.responseTime = Duration.ZERO;
        this.concurrencyLevel = 1;
    }

    // Constructor with basic metrics
    public TestMetrics(Duration responseTime, int successCount, int errorCount, int concurrencyLevel) {
        this();
        this.responseTime = responseTime != null ? responseTime : Duration.ZERO;
        this.successCount = successCount;
        this.errorCount = errorCount;
        this.concurrencyLevel = concurrencyLevel;
    }

    // Full constructor
    public TestMetrics(Duration responseTime, Integer successCount, Integer errorCount,
            Map<String, Integer> errorTypes, Integer concurrencyLevel, Instant timestamp) {
        this.responseTime = responseTime != null ? responseTime : Duration.ZERO;
        this.successCount = successCount != null ? successCount : 0;
        this.errorCount = errorCount != null ? errorCount : 0;
        this.errorTypes = errorTypes != null ? new ConcurrentHashMap<>(errorTypes) : new ConcurrentHashMap<>();
        this.concurrencyLevel = concurrencyLevel != null ? concurrencyLevel : 1;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    // Getters and Setters
    public Duration getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Duration responseTime) {
        this.responseTime = responseTime;
    }

    public Integer getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Integer successCount) {
        this.successCount = successCount;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Integer errorCount) {
        this.errorCount = errorCount;
    }

    public Map<String, Integer> getErrorTypes() {
        return new HashMap<>(errorTypes);
    }

    public void setErrorTypes(Map<String, Integer> errorTypes) {
        this.errorTypes = errorTypes != null ? new ConcurrentHashMap<>(errorTypes) : new ConcurrentHashMap<>();
    }

    public Integer getConcurrencyLevel() {
        return concurrencyLevel;
    }

    public void setConcurrencyLevel(Integer concurrencyLevel) {
        this.concurrencyLevel = concurrencyLevel;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    // Calculation and utility methods

    /**
     * Calculates the total number of operations (success + error).
     * 
     * @return total operations count
     */
    public int getTotalOperations() {
        return successCount + errorCount;
    }

    /**
     * Calculates the success rate as a percentage.
     * 
     * @return success rate (0.0 to 100.0)
     */
    public double getSuccessRate() {
        int total = getTotalOperations();
        return total > 0 ? (successCount * 100.0) / total : 0.0;
    }

    /**
     * Calculates the error rate as a percentage.
     * 
     * @return error rate (0.0 to 100.0)
     */
    public double getErrorRate() {
        int total = getTotalOperations();
        return total > 0 ? (errorCount * 100.0) / total : 0.0;
    }

    /**
     * Calculates the average response time in milliseconds.
     * 
     * @return average response time in milliseconds
     */
    public double getAverageResponseTimeMs() {
        int total = getTotalOperations();
        return total > 0 ? responseTime.toMillis() / (double) total : 0.0;
    }

    /**
     * Calculates the throughput in operations per second.
     * 
     * @return operations per second
     */
    public double getThroughputPerSecond() {
        if (responseTime.isZero() || responseTime.isNegative()) {
            return 0.0;
        }
        int total = getTotalOperations();
        double seconds = responseTime.toMillis() / 1000.0;
        return total / seconds;
    }

    // Error management methods

    /**
     * Adds an error of the specified type.
     * 
     * @param errorType the type of error
     */
    public void addError(String errorType) {
        if (errorType != null && !errorType.trim().isEmpty()) {
            errorTypes.merge(errorType.trim(), 1, Integer::sum);
            errorCount++;
        }
    }

    /**
     * Adds multiple errors of the specified type.
     * 
     * @param errorType the type of error
     * @param count     the number of errors to add
     */
    public void addErrors(String errorType, int count) {
        if (errorType != null && !errorType.trim().isEmpty() && count > 0) {
            errorTypes.merge(errorType.trim(), count, Integer::sum);
            errorCount += count;
        }
    }

    /**
     * Gets the count of errors for a specific type.
     * 
     * @param errorType the error type
     * @return the count of errors for this type
     */
    public int getErrorCount(String errorType) {
        return errorTypes.getOrDefault(errorType, 0);
    }

    /**
     * Gets the count of capacity exceeded errors.
     * 
     * @return capacity exceeded error count
     */
    public int getCapacityExceededErrors() {
        return getErrorCount(ERROR_TYPE_CAPACITY_EXCEEDED);
    }

    /**
     * Gets the count of duplicate key errors.
     * 
     * @return duplicate key error count
     */
    public int getDuplicateKeyErrors() {
        return getErrorCount(ERROR_TYPE_DUPLICATE_KEY);
    }

    /**
     * Gets the count of throttling errors.
     * 
     * @return throttling error count
     */
    public int getThrottlingErrors() {
        return getErrorCount(ERROR_TYPE_THROTTLING);
    }

    /**
     * Adds a successful operation.
     */
    public void addSuccess() {
        successCount++;
    }

    /**
     * Adds multiple successful operations.
     * 
     * @param count the number of successful operations to add
     */
    public void addSuccesses(int count) {
        if (count > 0) {
            successCount += count;
        }
    }

    /**
     * Merges another TestMetrics instance into this one.
     * 
     * @param other the other metrics to merge
     */
    public void merge(TestMetrics other) {
        if (other == null)
            return;

        this.successCount += other.successCount;
        this.errorCount += other.errorCount;
        this.responseTime = this.responseTime.plus(other.responseTime);

        // Merge error types
        for (Map.Entry<String, Integer> entry : other.errorTypes.entrySet()) {
            this.errorTypes.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        // Update timestamp to the latest
        if (other.timestamp.isAfter(this.timestamp)) {
            this.timestamp = other.timestamp;
        }
    }

    /**
     * Creates a snapshot of the current metrics.
     * 
     * @return a new TestMetrics instance with the same values
     */
    public TestMetrics createSnapshot() {
        return new TestMetrics(
                Duration.ofMillis(this.responseTime.toMillis()),
                this.successCount,
                this.errorCount,
                new HashMap<>(this.errorTypes),
                this.concurrencyLevel,
                this.timestamp);
    }

    /**
     * Resets all metrics to zero/empty state.
     */
    public void reset() {
        this.successCount = 0;
        this.errorCount = 0;
        this.responseTime = Duration.ZERO;
        this.errorTypes.clear();
        this.timestamp = Instant.now();
    }

    /**
     * Validates that the metrics are in a consistent state.
     * 
     * @return true if metrics are valid
     */
    public boolean isValid() {
        if (responseTime == null || responseTime.isNegative() ||
                successCount == null || successCount < 0 ||
                errorCount == null || errorCount < 0 ||
                concurrencyLevel == null || concurrencyLevel < 1 ||
                timestamp == null || errorTypes == null) {
            return false;
        }

        // Validate that error types sum matches error count
        int errorTypeSum = errorTypes.values().stream().mapToInt(Integer::intValue).sum();
        return errorTypeSum <= errorCount; // Allow for some errors not categorized
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TestMetrics that = (TestMetrics) o;
        return Objects.equals(responseTime, that.responseTime) &&
                Objects.equals(successCount, that.successCount) &&
                Objects.equals(errorCount, that.errorCount) &&
                Objects.equals(errorTypes, that.errorTypes) &&
                Objects.equals(concurrencyLevel, that.concurrencyLevel) &&
                Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(responseTime, successCount, errorCount, errorTypes, concurrencyLevel, timestamp);
    }

    @Override
    public String toString() {
        return "TestMetrics{" +
                "responseTime=" + responseTime +
                ", successCount=" + successCount +
                ", errorCount=" + errorCount +
                ", errorTypes=" + errorTypes +
                ", concurrencyLevel=" + concurrencyLevel +
                ", timestamp=" + timestamp +
                ", totalOperations=" + getTotalOperations() +
                ", successRate=" + String.format("%.2f%%", getSuccessRate()) +
                ", errorRate=" + String.format("%.2f%%", getErrorRate()) +
                ", avgResponseTimeMs=" + String.format("%.2f", getAverageResponseTimeMs()) +
                ", throughputPerSec=" + String.format("%.2f", getThroughputPerSecond()) +
                '}';
    }
}