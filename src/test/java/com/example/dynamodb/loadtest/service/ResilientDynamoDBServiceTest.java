package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestItem;
import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.repository.DynamoDBRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResilientDynamoDBServiceTest {

    @Mock
    private DynamoDBRepository dynamoDBRepository;

    @Mock
    private ErrorHandler errorHandler;

    @Mock
    private CircuitBreaker circuitBreaker;

    private ResilientDynamoDBService resilientService;
    private TestItem testItem;
    private TestMetrics testMetrics;

    @BeforeEach
    void setUp() {
        resilientService = new ResilientDynamoDBService(dynamoDBRepository, errorHandler, circuitBreaker);

        testItem = new TestItem();
        testItem.setPrimaryKey("test-key");
        testItem.setPayload("test-payload");
        testItem.setTimestamp(Instant.now());

        testMetrics = new TestMetrics();
    }

    @Test
    void testPutItemWithResilience_Success() throws Exception {
        // Arrange
        PutItemResponse expectedResponse = PutItemResponse.builder().build();
        CompletableFuture<PutItemResponse> repositoryFuture = CompletableFuture.completedFuture(expectedResponse);

        when(dynamoDBRepository.putItem(testItem)).thenReturn(repositoryFuture);
        when(errorHandler.executeWithRetry(any()))
                .thenAnswer(invocation -> {
                    ErrorHandler.RetryableOperation<PutItemResponse> operation = invocation.getArgument(0);
                    return operation.execute();
                });

        // Act
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithResilience(testItem, testMetrics);
        PutItemResponse response = result.get();

        // Assert
        assertNotNull(response);
        assertEquals(1, testMetrics.getSuccessCount());
        assertEquals(0, testMetrics.getErrorCount());
        assertTrue(testMetrics.getResponseTime().toMillis() >= 0);

        // Verify direct execution path was used (no circuit breaker needed)
        verify(dynamoDBRepository).putItem(testItem);
        verify(errorHandler).executeWithRetry(any());
    }

    @Test
    void testPutItemWithResilience_UseFallback() throws Exception {
        // Arrange - simulate direct execution failure, then circuit breaker with
        // fallback
        RuntimeException directException = new RuntimeException("Direct execution failed");
        when(errorHandler.executeWithRetry(any()))
                .thenThrow(directException);

        when(circuitBreaker.execute(any(), any()))
                .thenAnswer(invocation -> {
                    CircuitBreaker.CircuitBreakerOperation<PutItemResponse> fallback = invocation.getArgument(1);
                    return fallback.execute();
                });

        // Act
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithResilience(testItem, testMetrics);
        PutItemResponse response = result.get();

        // Assert
        assertNotNull(response);
        assertEquals(1, testMetrics.getSuccessCount());
        assertEquals(0, testMetrics.getErrorCount());

        verify(circuitBreaker).execute(any(), any());
    }

    @Test
    void testPutItemWithResilience_Failure() throws Exception {
        // Arrange
        ProvisionedThroughputExceededException exception = ProvisionedThroughputExceededException.builder()
                .message("Capacity exceeded")
                .build();

        when(errorHandler.executeWithRetry(any()))
                .thenThrow(exception);
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class),
                any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenThrow(exception);

        // Act & Assert
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithResilience(testItem, testMetrics);

        assertThrows(ExecutionException.class, () -> result.get());

        assertEquals(0, testMetrics.getSuccessCount());
        assertTrue(testMetrics.getResponseTime().toMillis() >= 0);

        verify(errorHandler).handleDynamoDBError(eq(exception), eq(testMetrics));
    }

    @Test
    void testPutItemWithCircuitBreaker_Success() throws Exception {
        // Arrange
        PutItemResponse expectedResponse = PutItemResponse.builder().build();
        CompletableFuture<PutItemResponse> repositoryFuture = CompletableFuture.completedFuture(expectedResponse);

        when(dynamoDBRepository.putItem(testItem)).thenReturn(repositoryFuture);
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenAnswer(invocation -> {
                    CircuitBreaker.CircuitBreakerOperation<PutItemResponse> operation = invocation.getArgument(0);
                    return operation.execute();
                });
        when(errorHandler.executeWithRetry(any(ErrorHandler.RetryableOperation.class)))
                .thenAnswer(invocation -> {
                    ErrorHandler.RetryableOperation<PutItemResponse> operation = invocation.getArgument(0);
                    return operation.execute();
                });

        // Act
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithCircuitBreaker(testItem, testMetrics);
        PutItemResponse response = result.get();

        // Assert
        assertNotNull(response);
        assertEquals(1, testMetrics.getSuccessCount());
        assertEquals(0, testMetrics.getErrorCount());

        verify(circuitBreaker).execute(any(CircuitBreaker.CircuitBreakerOperation.class));
        verify(errorHandler).executeWithRetry(any(ErrorHandler.RetryableOperation.class));
    }

    @Test
    void testPutItemWithCircuitBreaker_CircuitOpen() throws Exception {
        // Arrange
        CircuitBreaker.CircuitBreakerOpenException exception = new CircuitBreaker.CircuitBreakerOpenException(
                "Circuit is open");

        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenThrow(exception);

        // Act & Assert
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithCircuitBreaker(testItem, testMetrics);

        assertThrows(ExecutionException.class, () -> result.get());

        assertEquals(0, testMetrics.getSuccessCount());
        verify(errorHandler).handleDynamoDBError(eq(exception), eq(testMetrics));
    }

    @Test
    void testTableExistsWithResilience_Success() throws Exception {
        // Arrange
        CompletableFuture<Boolean> repositoryFuture = CompletableFuture.completedFuture(true);
        when(dynamoDBRepository.tableExists("test-table")).thenReturn(repositoryFuture);
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class),
                any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenAnswer(invocation -> {
                    CircuitBreaker.CircuitBreakerOperation<Boolean> operation = invocation.getArgument(0);
                    return operation.execute();
                });

        // Act
        CompletableFuture<Boolean> result = resilientService.tableExistsWithResilience("test-table");
        Boolean exists = result.get();

        // Assert
        assertTrue(exists);
        verify(dynamoDBRepository).tableExists("test-table");
    }

    @Test
    void testTableExistsWithResilience_UseFallback() throws Exception {
        // Arrange
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class),
                any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenAnswer(invocation -> {
                    CircuitBreaker.CircuitBreakerOperation<Boolean> fallback = invocation.getArgument(1);
                    return fallback.execute();
                });

        // Act
        CompletableFuture<Boolean> result = resilientService.tableExistsWithResilience("test-table");
        Boolean exists = result.get();

        // Assert
        assertTrue(exists); // Fallback returns true
        verify(dynamoDBRepository, never()).tableExists(any());
    }

    @Test
    void testTableExistsWithResilience_Exception() throws Exception {
        // Arrange
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class),
                any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenThrow(new RuntimeException("Circuit breaker error"));

        // Act
        CompletableFuture<Boolean> result = resilientService.tableExistsWithResilience("test-table");
        Boolean exists = result.get();

        // Assert
        assertTrue(exists); // Should default to true on exception
    }

    @Test
    void testGetCircuitBreakerState() {
        // Arrange
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

        // Act
        CircuitBreaker.State state = resilientService.getCircuitBreakerState();

        // Assert
        assertEquals(CircuitBreaker.State.CLOSED, state);
        verify(circuitBreaker).getState();
    }

    @Test
    void testGetCircuitBreakerStats() {
        // Arrange
        CircuitBreaker.CircuitBreakerStats expectedStats = new CircuitBreaker.CircuitBreakerStats(
                CircuitBreaker.State.CLOSED, 0, 0, 0, null);
        when(circuitBreaker.getStats()).thenReturn(expectedStats);

        // Act
        CircuitBreaker.CircuitBreakerStats stats = resilientService.getCircuitBreakerStats();

        // Assert
        assertEquals(expectedStats, stats);
        verify(circuitBreaker).getStats();
    }

    @Test
    void testResetCircuitBreaker() {
        // Act
        resilientService.resetCircuitBreaker();

        // Assert
        verify(circuitBreaker).reset();
    }

    @Test
    void testOpenCircuitBreaker() {
        // Act
        resilientService.openCircuitBreaker();

        // Assert
        verify(circuitBreaker).forceOpen();
    }

    @Test
    void testIsServiceAvailable() {
        // Arrange
        when(circuitBreaker.canExecute()).thenReturn(true);

        // Act
        boolean available = resilientService.isServiceAvailable();

        // Assert
        assertTrue(available);
        verify(circuitBreaker).canExecute();
    }

    @Test
    void testGetters() {
        // Act & Assert
        assertEquals(dynamoDBRepository, resilientService.getDynamoDBRepository());
        assertEquals(errorHandler, resilientService.getErrorHandler());
        assertEquals(circuitBreaker, resilientService.getCircuitBreaker());
    }

    @Test
    void testExecuteWithRetry_CompletionException() throws Exception {
        // Arrange
        ProvisionedThroughputExceededException cause = ProvisionedThroughputExceededException.builder().build();
        CompletionException completionException = new CompletionException(cause);
        CompletableFuture<PutItemResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(completionException);

        when(dynamoDBRepository.putItem(testItem)).thenReturn(failedFuture);
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class),
                any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenAnswer(invocation -> {
                    CircuitBreaker.CircuitBreakerOperation<PutItemResponse> operation = invocation.getArgument(0);
                    return operation.execute();
                });
        when(errorHandler.executeWithRetry(any(ErrorHandler.RetryableOperation.class)))
                .thenAnswer(invocation -> {
                    ErrorHandler.RetryableOperation<PutItemResponse> operation = invocation.getArgument(0);
                    return operation.execute();
                });

        // Act & Assert
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithResilience(testItem, testMetrics);

        assertThrows(ExecutionException.class, () -> result.get());
        assertEquals(0, testMetrics.getSuccessCount());
    }

    @Test
    void testExecuteWithRetry_UnwrapsException() throws Exception {
        // Arrange
        RuntimeException cause = new RuntimeException("Test exception");
        CompletionException completionException = new CompletionException(cause);
        CompletableFuture<PutItemResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(completionException);

        when(dynamoDBRepository.putItem(testItem)).thenReturn(failedFuture);
        when(errorHandler.executeWithRetry(any(ErrorHandler.RetryableOperation.class)))
                .thenAnswer(invocation -> {
                    ErrorHandler.RetryableOperation<PutItemResponse> operation = invocation.getArgument(0);
                    try {
                        return operation.execute();
                    } catch (RuntimeException e) {
                        // Verify that the cause is properly unwrapped
                        assertEquals("Test exception", e.getMessage());
                        throw e;
                    }
                });
        when(circuitBreaker.execute(any(CircuitBreaker.CircuitBreakerOperation.class),
                any(CircuitBreaker.CircuitBreakerOperation.class)))
                .thenAnswer(invocation -> {
                    CircuitBreaker.CircuitBreakerOperation<PutItemResponse> operation = invocation.getArgument(0);
                    return operation.execute();
                });

        // Act & Assert
        CompletableFuture<PutItemResponse> result = resilientService.putItemWithResilience(testItem, testMetrics);

        assertThrows(ExecutionException.class, () -> result.get());
    }
}