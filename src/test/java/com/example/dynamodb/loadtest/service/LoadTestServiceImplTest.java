package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.model.TestItem;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LoadTestServiceImplTest {

    @Mock
    private MetricsCollectionService metricsCollectionService;

    @Mock
    private ResilientDynamoDBService resilientDynamoDBService;

    @Mock
    private AccurateDuplicateCounter accurateDuplicateCounter;

    private LoadTestServiceImpl loadTestService;

    @BeforeEach
    void setUp() {
        Executor mockExecutor = Runnable::run; // Simple synchronous executor for testing

        // Mock the ResilientDynamoDBService to return successful CompletableFuture
        // (lenient for tests that don't use it)
        lenient().when(resilientDynamoDBService.putItemWithResilience(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(resilientDynamoDBService.putItemWithEnhancedResilience(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        loadTestService = new LoadTestServiceImpl(metricsCollectionService, resilientDynamoDBService,
                accurateDuplicateCounter, mockExecutor);
        loadTestService.resetKeyCounter(); // Reset counter for consistent tests
    }

    @Test
    void generateUniqueKey_ReturnsUniqueKeys() {
        // Act
        String key1 = loadTestService.generateUniqueKey();
        String key2 = loadTestService.generateUniqueKey();
        String key3 = loadTestService.generateUniqueKey();

        // Assert
        assertNotNull(key1);
        assertNotNull(key2);
        assertNotNull(key3);
        assertNotEquals(key1, key2);
        assertNotEquals(key2, key3);
        assertNotEquals(key1, key3);

        // Check format
        assertTrue(key1.startsWith("test-item-"));
        assertTrue(key2.startsWith("test-item-"));
        assertTrue(key3.startsWith("test-item-"));
    }

    @Test
    void generateDuplicateKey_WithExistingKeys_ReturnsDuplicateKey() {
        // Arrange
        List<String> existingKeys = List.of("key1", "key2", "key3");

        // Act
        String duplicateKey = loadTestService.generateDuplicateKey(existingKeys);

        // Assert
        assertNotNull(duplicateKey);
        assertTrue(existingKeys.contains(duplicateKey));
    }

    @Test
    void generateDuplicateKey_WithEmptyKeys_ReturnsUniqueKey() {
        // Arrange
        List<String> existingKeys = new ArrayList<>();

        // Act
        String key = loadTestService.generateDuplicateKey(existingKeys);

        // Assert
        assertNotNull(key);
        assertTrue(key.startsWith("test-item-"));
    }

    @Test
    void generatePayload_ValidSize_ReturnsCorrectSizePayload() {
        // Arrange
        int targetSize = 100;

        // Act
        String payload = loadTestService.generatePayload(targetSize);

        // Assert
        assertNotNull(payload);
        assertEquals(targetSize, payload.length());
    }

    @Test
    void generatePayload_ZeroSize_ReturnsEmptyString() {
        // Act
        String payload = loadTestService.generatePayload(0);

        // Assert
        assertEquals("", payload);
    }

    @Test
    void generatePayload_NegativeSize_ReturnsEmptyString() {
        // Act
        String payload = loadTestService.generatePayload(-10);

        // Assert
        assertEquals("", payload);
    }

    @Test
    void generateTestItems_WithoutDuplicates_GeneratesUniqueItems() {
        // Arrange
        int count = 10;
        double duplicatePercentage = 0.0;

        // Act
        List<TestItem> items = loadTestService.generateTestItems(count, duplicatePercentage);

        // Assert
        assertEquals(count, items.size());

        // Check all keys are unique
        Set<String> uniqueKeys = new HashSet<>();
        for (TestItem item : items) {
            assertNotNull(item.getPrimaryKey());
            assertNotNull(item.getPayload());
            assertNotNull(item.getTimestamp());
            assertFalse((Boolean) item.getAttribute("is_duplicate"));
            assertTrue(uniqueKeys.add(item.getPrimaryKey()), "Duplicate key found: " + item.getPrimaryKey());
        }
    }

    @Test
    void generateTestItems_WithDuplicates_GeneratesCorrectMix() {
        // Arrange
        int count = 100;
        double duplicatePercentage = 20.0; // 20% duplicates

        // Act
        List<TestItem> items = loadTestService.generateTestItems(count, duplicatePercentage);

        // Assert
        assertEquals(count, items.size());

        int duplicateCount = 0;
        int uniqueCount = 0;

        for (TestItem item : items) {
            assertNotNull(item.getPrimaryKey());
            assertNotNull(item.getPayload());
            assertNotNull(item.getTimestamp());

            Boolean isDuplicate = (Boolean) item.getAttribute("is_duplicate");
            assertNotNull(isDuplicate);

            if (isDuplicate) {
                duplicateCount++;
            } else {
                uniqueCount++;
            }
        }

        // Check that we have the expected number of duplicates (approximately)
        int expectedDuplicates = (int) Math.ceil(count * (duplicatePercentage / 100.0));
        assertEquals(expectedDuplicates, duplicateCount);
        assertEquals(count - expectedDuplicates, uniqueCount);
    }

    @Test
    void generateTestItems_ZeroCount_ReturnsEmptyList() {
        // Act
        List<TestItem> items = loadTestService.generateTestItems(0, 0.0);

        // Assert
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    void executeWithConcurrency_ValidItems_CompletesSuccessfully() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = List.of(
                new TestItem("key1", "payload1"),
                new TestItem("key2", "payload2"),
                new TestItem("key3", "payload3"));
        int concurrency = 2;

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);

        // Assert
        assertDoesNotThrow(() -> result.get());
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void executeWithConcurrency_EmptyItems_CompletesSuccessfully() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = new ArrayList<>();
        int concurrency = 5;

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);

        // Assert
        assertDoesNotThrow(() -> result.get());
        assertTrue(result.isDone());
    }

    @Test
    void executeLoadTest_ValidConfiguration_CompletesSuccessfully() throws ExecutionException, InterruptedException {
        // Arrange
        TestConfiguration config = new TestConfiguration(
                "test-table", 10, 50, 75.0, 25.0, true, "test");

        TestSummary mockSummary = new TestSummary(
                50, 45, 5,
                java.util.Map.of("DuplicateKey", 5L),
                java.time.Duration.ofSeconds(10),
                java.time.Instant.now().minusSeconds(10),
                java.time.Instant.now(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.time.Duration.ofMillis(100),
                5.0);

        when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

        // Act
        CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
        TestSummary summary = result.get();

        // Assert
        assertNotNull(summary);
        assertEquals(mockSummary, summary);

        // Verify metrics service interactions
        verify(metricsCollectionService).startTest();
        verify(metricsCollectionService).endTest();
        verify(metricsCollectionService).generateSummary();
    }

    @Test
    void executeLoadTest_ServiceException_HandlesGracefully() {
        // Arrange
        TestConfiguration config = new TestConfiguration(
                "test-table", 10, 50, 75.0, 25.0, true, "test");

        doThrow(new RuntimeException("Metrics service error")).when(metricsCollectionService).startTest();

        // Act & Assert
        CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);

        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof RuntimeException);
        assertTrue(exception.getCause().getMessage().contains("Load test execution failed"));

        // Verify that endTest is still called even on error
        verify(metricsCollectionService).endTest();
    }

    @Test
    void getCurrentKeyCounter_ReturnsCorrectValue() {
        // Arrange
        loadTestService.resetKeyCounter();

        // Act
        long initialCounter = loadTestService.getCurrentKeyCounter();
        loadTestService.generateUniqueKey();
        loadTestService.generateUniqueKey();
        long afterCounter = loadTestService.getCurrentKeyCounter();

        // Assert
        assertEquals(0, initialCounter);
        assertEquals(2, afterCounter);
    }

    @Test
    void resetKeyCounter_ResetsToZero() {
        // Arrange
        loadTestService.generateUniqueKey();
        loadTestService.generateUniqueKey();
        assertTrue(loadTestService.getCurrentKeyCounter() > 0);

        // Act
        loadTestService.resetKeyCounter();

        // Assert
        assertEquals(0, loadTestService.getCurrentKeyCounter());
    }

    @Test
    void generateTestItems_LargeCount_PerformanceTest() {
        // Arrange
        int count = 1000;
        double duplicatePercentage = 10.0;

        // Act
        long startTime = System.currentTimeMillis();
        List<TestItem> items = loadTestService.generateTestItems(count, duplicatePercentage);
        long endTime = System.currentTimeMillis();

        // Assert
        assertEquals(count, items.size());
        assertTrue(endTime - startTime < 5000, "Generation took too long: " + (endTime - startTime) + "ms");

        // Verify all items are valid
        for (TestItem item : items) {
            assertTrue(item.isValid());
            assertTrue(item.getApproximateSize() > 0);
        }
    }

    @Test
    void generateTestItems_HighDuplicatePercentage_HandlesCorrectly() {
        // Arrange
        int count = 20;
        double duplicatePercentage = 90.0; // Very high percentage

        // Act
        List<TestItem> items = loadTestService.generateTestItems(count, duplicatePercentage);

        // Assert
        assertEquals(count, items.size());

        long duplicateCount = items.stream()
                .mapToLong(item -> (Boolean) item.getAttribute("is_duplicate") ? 1 : 0)
                .sum();

        int expectedDuplicates = (int) Math.ceil(count * (duplicatePercentage / 100.0));
        assertEquals(expectedDuplicates, duplicateCount);
    }
}