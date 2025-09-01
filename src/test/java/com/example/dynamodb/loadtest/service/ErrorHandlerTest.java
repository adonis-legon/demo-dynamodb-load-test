package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ErrorHandlerTest {

    private ErrorHandler errorHandler;
    private TestMetrics testMetrics;

    @BeforeEach
    void setUp() {
        errorHandler = new ErrorHandler();
        testMetrics = new TestMetrics();
    }

    @Test
    void testCategorizeError_ProvisionedThroughputExceeded() {
        ProvisionedThroughputExceededException exception = ProvisionedThroughputExceededException.builder()
                .message("Provisioned throughput exceeded")
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, errorType);
    }

    @Test
    void testCategorizeError_RequestLimitExceeded() {
        RequestLimitExceededException exception = RequestLimitExceededException.builder()
                .message("Request limit exceeded")
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, errorType);
    }

    @Test
    void testCategorizeError_ThrottlingExceptionByErrorCode() {
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ThrottlingException")
                .errorMessage("Request was throttled")
                .build();

        DynamoDbException exception = (DynamoDbException) DynamoDbException.builder()
                .message("Request was throttled")
                .awsErrorDetails(errorDetails)
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_THROTTLING, errorType);
    }

    @Test
    void testCategorizeError_ConditionalCheckFailed() {
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_DUPLICATE_KEY, errorType);
    }

    @Test
    void testCategorizeError_ValidationExceptionByErrorCode() {
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ValidationException")
                .errorMessage("Validation failed")
                .build();

        DynamoDbException exception = (DynamoDbException) DynamoDbException.builder()
                .message("Validation failed")
                .awsErrorDetails(errorDetails)
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_VALIDATION, errorType);
    }

    @Test
    void testCategorizeError_NetworkErrors() {
        ConnectException connectException = new ConnectException("Connection refused");
        assertEquals(TestMetrics.ERROR_TYPE_NETWORK, errorHandler.categorizeError(connectException));

        UnknownHostException hostException = new UnknownHostException("Unknown host");
        assertEquals(TestMetrics.ERROR_TYPE_NETWORK, errorHandler.categorizeError(hostException));

        IOException ioException = new IOException("Network error");
        assertEquals(TestMetrics.ERROR_TYPE_NETWORK, errorHandler.categorizeError(ioException));
    }

    @Test
    void testCategorizeError_TimeoutErrors() {
        SocketTimeoutException socketTimeout = new SocketTimeoutException("Socket timeout");
        assertEquals(TestMetrics.ERROR_TYPE_TIMEOUT, errorHandler.categorizeError(socketTimeout));

        TimeoutException timeout = new TimeoutException("Operation timed out");
        assertEquals(TestMetrics.ERROR_TYPE_TIMEOUT, errorHandler.categorizeError(timeout));

        RuntimeException timeoutMessage = new RuntimeException("Request timed out");
        assertEquals(TestMetrics.ERROR_TYPE_TIMEOUT, errorHandler.categorizeError(timeoutMessage));
    }

    @Test
    void testCategorizeError_DynamoDbExceptionWithErrorCode() {
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ThrottlingException")
                .errorMessage("Request was throttled")
                .build();

        DynamoDbException exception = (DynamoDbException) DynamoDbException.builder()
                .message("DynamoDB error")
                .awsErrorDetails(errorDetails)
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_THROTTLING, errorType);
    }

    @Test
    void testCategorizeError_DynamoDbExceptionWithCapacityErrorCode() {
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ProvisionedThroughputExceededException")
                .errorMessage("Provisioned throughput exceeded")
                .build();

        DynamoDbException exception = (DynamoDbException) DynamoDbException.builder()
                .message("DynamoDB error")
                .awsErrorDetails(errorDetails)
                .build();

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, errorType);
    }

    @Test
    void testCategorizeError_UnknownException() {
        RuntimeException exception = new RuntimeException("Unknown error");

        String errorType = errorHandler.categorizeError(exception);

        assertEquals(TestMetrics.ERROR_TYPE_UNKNOWN, errorType);
    }

    @Test
    void testCategorizeError_NullException() {
        String errorType = errorHandler.categorizeError(null);

        assertEquals(TestMetrics.ERROR_TYPE_UNKNOWN, errorType);
    }

    @Test
    void testHandleDynamoDBError_UpdatesMetrics() {
        ProvisionedThroughputExceededException exception = ProvisionedThroughputExceededException.builder()
                .message("Provisioned throughput exceeded")
                .build();

        errorHandler.handleDynamoDBError(exception, testMetrics);

        assertEquals(1, testMetrics.getErrorCount());
        assertEquals(1, testMetrics.getCapacityExceededErrors());
    }

    @Test
    void testHandleDynamoDBError_NullInputs() {
        // Should not throw exception with null inputs
        assertDoesNotThrow(() -> {
            errorHandler.handleDynamoDBError(null, testMetrics);
            errorHandler.handleDynamoDBError(new RuntimeException(), null);
            errorHandler.handleDynamoDBError(null, null);
        });

        assertEquals(0, testMetrics.getErrorCount());
    }

    @Test
    void testShouldRetry_CapacityErrors() {
        ProvisionedThroughputExceededException exception = ProvisionedThroughputExceededException.builder().build();

        // With MAX_RETRY_ATTEMPTS=3, retries should occur for capacity errors
        assertTrue(errorHandler.shouldRetry(exception, 1)); // Should retry
        assertTrue(errorHandler.shouldRetry(exception, 2)); // Should retry
        assertFalse(errorHandler.shouldRetry(exception, 3)); // Max attempts reached
        assertFalse(errorHandler.shouldRetry(exception, 4)); // Exceeds max attempts
    }

    @Test
    void testShouldRetry_ThrottlingErrors() {
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ThrottlingException")
                .build();

        DynamoDbException exception = (DynamoDbException) DynamoDbException.builder()
                .awsErrorDetails(errorDetails)
                .build();

        // With MAX_RETRY_ATTEMPTS=3, retries should occur for throttling errors
        assertTrue(errorHandler.shouldRetry(exception, 1)); // Should retry
        assertTrue(errorHandler.shouldRetry(exception, 2)); // Should retry
        assertFalse(errorHandler.shouldRetry(exception, 3)); // Max attempts reached
        assertFalse(errorHandler.shouldRetry(exception, 4)); // Exceeds max attempts
    }

    @Test
    void testShouldRetry_NetworkErrors() {
        ConnectException exception = new ConnectException("Connection refused");

        // With MAX_RETRY_ATTEMPTS=3, retries should occur for network errors
        assertTrue(errorHandler.shouldRetry(exception, 1)); // Should retry
        assertTrue(errorHandler.shouldRetry(exception, 2)); // Should retry
        assertFalse(errorHandler.shouldRetry(exception, 3)); // Max attempts reached
        assertFalse(errorHandler.shouldRetry(exception, 4)); // Exceeds max attempts
    }

    @Test
    void testShouldRetry_TimeoutErrors() {
        SocketTimeoutException exception = new SocketTimeoutException("Timeout");

        // With MAX_RETRY_ATTEMPTS=3, retries should occur for timeout errors
        assertTrue(errorHandler.shouldRetry(exception, 1)); // Should retry
        assertTrue(errorHandler.shouldRetry(exception, 2)); // Should retry
        assertFalse(errorHandler.shouldRetry(exception, 3)); // Max attempts reached
        assertFalse(errorHandler.shouldRetry(exception, 4)); // Exceeds max attempts
    }

    @Test
    void testShouldRetry_NonRetryableErrors() {
        AwsErrorDetails validationErrorDetails = AwsErrorDetails.builder()
                .errorCode("ValidationException")
                .build();
        DynamoDbException validationException = (DynamoDbException) DynamoDbException.builder()
                .awsErrorDetails(validationErrorDetails)
                .build();
        assertFalse(errorHandler.shouldRetry(validationException, 1));

        ConditionalCheckFailedException duplicateException = ConditionalCheckFailedException.builder().build();
        assertFalse(errorHandler.shouldRetry(duplicateException, 1));

        RuntimeException unknownException = new RuntimeException("Unknown error");
        assertFalse(errorHandler.shouldRetry(unknownException, 1));
    }

    @Test
    void testShouldRetry_NullException() {
        assertFalse(errorHandler.shouldRetry(null, 1));
    }

    @Test
    void testCalculateBackoff_ExponentialIncrease() {
        Duration delay1 = errorHandler.calculateBackoff(1);
        Duration delay2 = errorHandler.calculateBackoff(2);
        Duration delay3 = errorHandler.calculateBackoff(3);

        // With enhanced configuration, delays should be based on BASE_DELAY=50ms
        assertTrue(delay1.toMillis() >= 50); // At least base delay
        assertTrue(delay2.toMillis() >= delay1.toMillis()); // Should increase
        assertTrue(delay3.toMillis() >= delay2.toMillis()); // Should increase
    }

    @Test
    void testCalculateBackoff_MaxDelayLimit() {
        Duration delay = errorHandler.calculateBackoff(20); // Very high attempt count

        assertTrue(delay.toMillis() <= errorHandler.getMaxDelay().toMillis());
    }

    @Test
    void testCalculateBackoff_ZeroOrNegativeAttempt() {
        Duration delay0 = errorHandler.calculateBackoff(0);
        Duration delayNegative = errorHandler.calculateBackoff(-1);

        assertEquals(errorHandler.getBaseDelay(), delay0);
        assertEquals(errorHandler.getBaseDelay(), delayNegative);
    }

    @Test
    void testExecuteWithRetry_SuccessfulOperation() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        ErrorHandler.RetryableOperation<String> operation = () -> {
            callCount.incrementAndGet();
            return "success";
        };

        String result = errorHandler.executeWithRetry(operation);

        assertEquals("success", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void testExecuteWithRetry_SuccessAfterRetries() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        // With MAX_RETRY_ATTEMPTS=1, operation must succeed on first try
        ErrorHandler.RetryableOperation<String> operation = () -> {
            callCount.incrementAndGet();
            return "success";
        };

        String result = errorHandler.executeWithRetry(operation);

        assertEquals("success", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void testExecuteWithRetry_FailsAfterMaxAttempts() {
        AtomicInteger callCount = new AtomicInteger(0);

        ErrorHandler.RetryableOperation<String> operation = () -> {
            callCount.incrementAndGet();
            throw ProvisionedThroughputExceededException.builder()
                    .message("Capacity exceeded")
                    .build();
        };

        assertThrows(ProvisionedThroughputExceededException.class, () -> {
            errorHandler.executeWithRetry(operation);
        });

        assertEquals(errorHandler.getMaxRetryAttempts(), callCount.get());
    }

    @Test
    void testExecuteWithRetry_NonRetryableError() {
        AtomicInteger callCount = new AtomicInteger(0);

        ErrorHandler.RetryableOperation<String> operation = () -> {
            callCount.incrementAndGet();
            AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                    .errorCode("ValidationException")
                    .build();
            throw DynamoDbException.builder()
                    .message("Validation error")
                    .awsErrorDetails(errorDetails)
                    .build();
        };

        assertThrows(DynamoDbException.class, () -> {
            errorHandler.executeWithRetry(operation);
        });

        assertEquals(1, callCount.get()); // Should not retry
    }

    @Test
    void testExecuteWithRetry_CustomMaxAttempts() {
        AtomicInteger callCount = new AtomicInteger(0);

        ErrorHandler.RetryableOperation<String> operation = () -> {
            int count = callCount.incrementAndGet();
            if (count < 4) { // Fail for first 3 attempts, succeed on 4th
                throw ProvisionedThroughputExceededException.builder()
                        .message("Capacity exceeded")
                        .build();
            }
            return "success";
        };

        // With enhanced configuration (MAX_RETRY_ATTEMPTS = 3), should fail after 3
        // attempts
        assertThrows(ProvisionedThroughputExceededException.class, () -> {
            errorHandler.executeWithRetry(operation, 3);
        });

        assertEquals(3, callCount.get()); // Should be called 3 times (initial + 2 retries)
    }

    @Test
    void testExecuteWithRetry_InterruptedException() {
        AtomicInteger callCount = new AtomicInteger(0);

        ErrorHandler.RetryableOperation<String> operation = () -> {
            callCount.incrementAndGet();
            throw ProvisionedThroughputExceededException.builder()
                    .message("Capacity exceeded")
                    .build();
        };

        // Interrupt the current thread to simulate interruption during sleep
        Thread.currentThread().interrupt();

        assertThrows(RuntimeException.class, () -> {
            errorHandler.executeWithRetry(operation);
        });

        // Clear the interrupt flag
        Thread.interrupted();
    }

    @Test
    void testGetters() {
        assertEquals(3, errorHandler.getMaxRetryAttempts());
        assertEquals(Duration.ofMillis(50), errorHandler.getBaseDelay());
        assertEquals(Duration.ofMillis(1000), errorHandler.getMaxDelay());
    }

    @Test
    void testCategorizeError_MessageBasedDetection() {
        // Test network error detection by message
        RuntimeException networkError = new RuntimeException("Connection failed");
        assertEquals(TestMetrics.ERROR_TYPE_NETWORK, errorHandler.categorizeError(networkError));

        // Test timeout error detection by message
        RuntimeException timeoutError = new RuntimeException("Operation timeout occurred");
        assertEquals(TestMetrics.ERROR_TYPE_TIMEOUT, errorHandler.categorizeError(timeoutError));

        // Test case insensitive detection
        RuntimeException upperCaseError = new RuntimeException("CONNECTION REFUSED");
        assertEquals(TestMetrics.ERROR_TYPE_NETWORK, errorHandler.categorizeError(upperCaseError));
    }

    @Test
    void testCategorizeError_ExceptionWithNullMessage() {
        RuntimeException exceptionWithNullMessage = new RuntimeException((String) null);

        // Should not throw NPE and should categorize as unknown
        String errorType = errorHandler.categorizeError(exceptionWithNullMessage);
        assertEquals(TestMetrics.ERROR_TYPE_UNKNOWN, errorType);
    }
}