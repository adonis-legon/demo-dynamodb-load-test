package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.exception.SSMAccessException;
import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.repository.SSMParameterRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigurationManagerTest {

    @Mock
    private SSMParameterRepository ssmParameterRepository;

    @Mock
    private Validator validator;

    private ConfigurationManager configurationManager;

    private static final String TEST_PREFIX = "/test/dynamodb-load-test";
    private static final String TEST_ENVIRONMENT = "test";

    @BeforeEach
    void setUp() {
        configurationManager = new ConfigurationManager(
                ssmParameterRepository,
                validator,
                TEST_PREFIX,
                TEST_ENVIRONMENT);
    }

    @Test
    void loadConfiguration_Success() throws ExecutionException, InterruptedException {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));
        when(validator.validate(any(TestConfiguration.class)))
                .thenReturn(Set.of()); // No violations

        // Act
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        TestConfiguration config = result.get();

        // Assert
        assertNotNull(config);
        assertEquals("test-table", config.getTableName());
        assertEquals(100, config.getConcurrencyLimit());
        assertEquals(1000, config.getTotalItems());
        assertEquals(75.0, config.getMaxConcurrencyPercentage());
        assertEquals(0.0, config.getDuplicatePercentage());
        assertFalse(config.getCleanupAfterTest());
        assertEquals(TEST_ENVIRONMENT, config.getEnvironment());

        verify(ssmParameterRepository).getParametersByPath(TEST_PREFIX);
        verify(validator).validate(any(TestConfiguration.class));
    }

    @Test
    void loadConfiguration_ValidationFailure() {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));

        @SuppressWarnings("unchecked")
        ConstraintViolation<TestConfiguration> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(mock(jakarta.validation.Path.class));
        when(violation.getMessage()).thenReturn("Test validation error");
        when(validator.validate(any(TestConfiguration.class)))
                .thenReturn(Set.of(violation));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Configuration validation failed"));
    }

    @Test
    void loadConfiguration_MissingRequiredParameter() {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        parameters.remove(TEST_PREFIX + "/table-name"); // Remove required parameter
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Required parameter not found"));
    }

    @Test
    void loadConfiguration_InvalidIntegerParameter() {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        parameters.put(TEST_PREFIX + "/concurrency-limit", "invalid-number");
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Invalid integer parameter"));
    }

    @Test
    void loadConfiguration_InvalidDoubleParameter() {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        parameters.put(TEST_PREFIX + "/max-concurrency-percentage", "invalid-double");
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Invalid double parameter"));
    }

    @Test
    void loadConfiguration_InvalidDoubleParameterForDuplicatePercentage() {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        parameters.put(TEST_PREFIX + "/duplicate-percentage", "invalid-double");
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Invalid double parameter"));
    }

    @Test
    void loadConfiguration_SSMAccessException() {
        // Arrange
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.failedFuture(new SSMAccessException("SSM access failed")));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof SSMAccessException);
    }

    @Test
    void isLocalEnvironment_LocalEnvironment() {
        // Arrange
        ConfigurationManager localConfigManager = new ConfigurationManager(
                ssmParameterRepository, validator, TEST_PREFIX, "local");

        // Act & Assert
        assertTrue(localConfigManager.isLocalEnvironment());
    }

    @Test
    void isLocalEnvironment_LocalhostEnvironment() {
        // Arrange
        ConfigurationManager localhostConfigManager = new ConfigurationManager(
                ssmParameterRepository, validator, TEST_PREFIX, "localhost-dev");

        // Act & Assert
        assertTrue(localhostConfigManager.isLocalEnvironment());
    }

    @Test
    void isLocalEnvironment_ProductionEnvironment() {
        // Arrange
        ConfigurationManager prodConfigManager = new ConfigurationManager(
                ssmParameterRepository, validator, TEST_PREFIX, "prod");

        // Act & Assert
        // Check if AWS_ENDPOINT_URL is set in the test environment
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        if (endpointUrl != null) {
            // If AWS_ENDPOINT_URL is set (e.g., during local build), should return true
            // even for prod environment due to the endpoint URL check
            assertTrue(prodConfigManager.isLocalEnvironment());
        } else {
            // Without AWS_ENDPOINT_URL set and environment not containing "local" or
            // "localhost", this should return false for production environment
            assertFalse(prodConfigManager.isLocalEnvironment());
        }
    }

    @Test
    void isLocalEnvironment_WithEndpointUrl() {
        // Act & Assert
        // Check if AWS_ENDPOINT_URL is actually set in the test environment
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        if (endpointUrl != null) {
            // If AWS_ENDPOINT_URL is set (e.g., in CI/CD with LocalStack), should return
            // true
            assertTrue(configurationManager.isLocalEnvironment());
        } else {
            // If AWS_ENDPOINT_URL is not set and environment is "test", should return false
            assertFalse(configurationManager.isLocalEnvironment());
        }
    }

    @Test
    void getEnvironment_ReturnsCorrectEnvironment() {
        // Act & Assert
        assertEquals(TEST_ENVIRONMENT, configurationManager.getEnvironment());
    }

    @Test
    void getParameterPrefix_ReturnsCorrectPrefix() {
        // Act & Assert
        assertEquals(TEST_PREFIX, configurationManager.getParameterPrefix());
    }

    @Test
    void loadConfiguration_ParametersWithoutPrefix() throws ExecutionException, InterruptedException {
        // Arrange - simulate parameters returned without full path prefix
        Map<String, String> parameters = new HashMap<>();
        parameters.put("table-name", "test-table");
        parameters.put("concurrency-limit", "100");
        parameters.put("total-items", "1000");
        parameters.put("max-concurrency-percentage", "75.0");
        parameters.put("duplicate-percentage", "25.0");
        parameters.put("cleanup-after-test", "false");

        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));
        when(validator.validate(any(TestConfiguration.class)))
                .thenReturn(Set.of()); // No violations

        // Act
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        TestConfiguration config = result.get();

        // Assert
        assertNotNull(config);
        assertEquals("test-table", config.getTableName());
        assertEquals(100, config.getConcurrencyLimit());
        assertEquals(25.0, config.getDuplicatePercentage());
    }

    @Test
    void loadConfiguration_EmptyParameterValue() {
        // Arrange
        Map<String, String> parameters = createValidParameterMap();
        parameters.put(TEST_PREFIX + "/table-name", "   "); // Empty/whitespace value
        when(ssmParameterRepository.getParametersByPath(TEST_PREFIX))
                .thenReturn(CompletableFuture.completedFuture(parameters));

        // Act & Assert
        CompletableFuture<TestConfiguration> result = configurationManager.loadConfiguration();
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Required parameter not found"));
    }

    private Map<String, String> createValidParameterMap() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(TEST_PREFIX + "/table-name", "test-table");
        parameters.put(TEST_PREFIX + "/concurrency-limit", "100");
        parameters.put(TEST_PREFIX + "/total-items", "1000");
        parameters.put(TEST_PREFIX + "/max-concurrency-percentage", "75.0");
        parameters.put(TEST_PREFIX + "/duplicate-percentage", "0.0");
        parameters.put(TEST_PREFIX + "/cleanup-after-test", "false");
        return parameters;
    }

}