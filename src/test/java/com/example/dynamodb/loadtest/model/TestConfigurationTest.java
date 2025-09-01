package com.example.dynamodb.loadtest.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TestConfiguration Model Tests")
class TestConfigurationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should create valid TestConfiguration with all fields")
    void shouldCreateValidTestConfigurationWithAllFields() {
        // Given
        TestConfiguration config = new TestConfiguration(
                "test-table",
                100,
                10000,
                50.0,
                25.0,
                true,
                "local");

        // Then
        assertEquals("test-table", config.getTableName());
        assertEquals(100, config.getConcurrencyLimit());
        assertEquals(10000, config.getTotalItems());
        assertEquals(50.0, config.getMaxConcurrencyPercentage());
        assertEquals(25.0, config.getDuplicatePercentage());
        assertEquals("local", config.getEnvironment());
        assertTrue(config.isValid());
    }

    @Test
    @DisplayName("Should validate table name constraints")
    void shouldValidateTableNameConstraints() {
        // Test blank table name
        TestConfiguration config = createValidConfig();
        config.setTableName("");
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Table name cannot be blank")));

        // Test null table name
        config.setTableName(null);
        violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Table name cannot be blank")));

        // Test table name too short
        config.setTableName("ab");
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Table name must be between 3 and 255 characters")));

        // Test table name too long
        config.setTableName("a".repeat(256));
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Table name must be between 3 and 255 characters")));
    }

    @Test
    @DisplayName("Should validate concurrency limit constraints")
    void shouldValidateConcurrencyLimitConstraints() {
        TestConfiguration config = createValidConfig();

        // Test null concurrency limit
        config.setConcurrencyLimit(null);
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Concurrency limit cannot be null")));

        // Test concurrency limit too low
        config.setConcurrencyLimit(0);
        violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Concurrency limit must be at least 1")));

        // Test concurrency limit too high
        config.setConcurrencyLimit(10001);
        violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Concurrency limit cannot exceed 10000")));
    }

    @Test
    @DisplayName("Should validate total items constraints")
    void shouldValidateTotalItemsConstraints() {
        TestConfiguration config = createValidConfig();

        // Test null total items
        config.setTotalItems(null);
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Total items cannot be null")));

        // Test total items too low
        config.setTotalItems(0);
        violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Total items must be at least 1")));

        // Test total items too high
        config.setTotalItems(10000001);
        violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Total items cannot exceed 10 million")));
    }

    @Test
    @DisplayName("Should validate max concurrency percentage constraints")
    void shouldValidateMaxConcurrencyPercentageConstraints() {
        TestConfiguration config = createValidConfig();

        // Test null percentage
        config.setMaxConcurrencyPercentage(null);
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Max concurrency percentage cannot be null")));

        // Test percentage too low
        config.setMaxConcurrencyPercentage(0.05);
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Max concurrency percentage must be at least 0.1%")));

        // Test percentage too high
        config.setMaxConcurrencyPercentage(100.1);
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Max concurrency percentage cannot exceed 100%")));
    }

    @Test
    @DisplayName("Should validate duplicate percentage constraints")
    void shouldValidateDuplicatePercentageConstraints() {
        TestConfiguration config = createValidConfig();

        // Test null duplicate percentage
        config.setDuplicatePercentage(null);
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Duplicate percentage cannot be null")));

        // Test percentage too low
        config.setDuplicatePercentage(-0.1);
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Duplicate percentage must be between 0% and 100%")));

        // Test percentage too high
        config.setDuplicatePercentage(100.1);
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("Duplicate percentage must be between 0% and 100%")));
    }

    @Test
    @DisplayName("Should validate environment constraints")
    void shouldValidateEnvironmentConstraints() {
        TestConfiguration config = createValidConfig();

        // Test blank environment
        config.setEnvironment("");
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Environment cannot be blank")));

        // Test null environment
        config.setEnvironment(null);
        violations = validator.validate(config);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Environment cannot be blank")));

        // Test invalid environment
        config.setEnvironment("invalid");
        violations = validator.validate(config);
        assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage()
                        .contains("Environment must be one of: local, dev, test, staging, prod, aws")));

        // Test valid environments
        String[] validEnvironments = { "local", "dev", "test", "staging", "prod" };
        for (String env : validEnvironments) {
            config.setEnvironment(env);
            violations = validator.validate(config);
            assertTrue(violations.stream().noneMatch(v -> v.getPropertyPath().toString().equals("environment")));
        }
    }

    @Test
    @DisplayName("Should calculate max concurrency level correctly")
    void shouldCalculateMaxConcurrencyLevelCorrectly() {
        TestConfiguration config = createValidConfig();
        config.setConcurrencyLimit(100);
        config.setMaxConcurrencyPercentage(50.0);

        assertEquals(50, config.getMaxConcurrencyLevel());

        // Test with percentage that requires ceiling
        config.setMaxConcurrencyPercentage(33.3);
        assertEquals(34, config.getMaxConcurrencyLevel()); // ceil(100 * 0.333) = 34

        // Test with null values
        config.setConcurrencyLimit(null);
        assertEquals(0, config.getMaxConcurrencyLevel());

        config.setConcurrencyLimit(100);
        config.setMaxConcurrencyPercentage(null);
        assertEquals(0, config.getMaxConcurrencyLevel());
    }

    @Test
    @DisplayName("Should calculate items for max concurrency correctly")
    void shouldCalculateItemsForMaxConcurrencyCorrectly() {
        TestConfiguration config = createValidConfig();
        config.setTotalItems(10000);
        config.setMaxConcurrencyPercentage(25.0);

        assertEquals(2500, config.getItemsForMaxConcurrency());

        // Test with percentage that requires ceiling
        config.setMaxConcurrencyPercentage(33.3);
        assertEquals(3330, config.getItemsForMaxConcurrency()); // ceil(10000 * 0.333) = 3330

        // Test with null values
        config.setTotalItems(null);
        assertEquals(0, config.getItemsForMaxConcurrency());

        config.setTotalItems(10000);
        config.setMaxConcurrencyPercentage(null);
        assertEquals(0, config.getItemsForMaxConcurrency());
    }

    @Test
    @DisplayName("Should calculate items for ramp up correctly")
    void shouldCalculateItemsForRampUpCorrectly() {
        TestConfiguration config = createValidConfig();
        config.setTotalItems(10000);
        config.setMaxConcurrencyPercentage(25.0);

        assertEquals(7500, config.getItemsForRampUp()); // 10000 - 2500

        // Test with null total items
        config.setTotalItems(null);
        assertEquals(0, config.getItemsForRampUp());
    }

    @Test
    @DisplayName("Should identify local environment correctly")
    void shouldIdentifyLocalEnvironmentCorrectly() {
        TestConfiguration config = createValidConfig();

        config.setEnvironment("local");
        assertTrue(config.isLocalEnvironment());

        config.setEnvironment("LOCAL");
        assertTrue(config.isLocalEnvironment());

        config.setEnvironment("prod");
        assertFalse(config.isLocalEnvironment());
    }

    @Test
    @DisplayName("Should identify production environment correctly")
    void shouldIdentifyProductionEnvironmentCorrectly() {
        TestConfiguration config = createValidConfig();

        config.setEnvironment("prod");
        assertTrue(config.isProductionEnvironment());

        config.setEnvironment("PROD");
        assertTrue(config.isProductionEnvironment());

        config.setEnvironment("local");
        assertFalse(config.isProductionEnvironment());
    }

    @Test
    @DisplayName("Should validate isValid method correctly")
    void shouldValidateIsValidMethodCorrectly() {
        // Valid configuration
        TestConfiguration validConfig = createValidConfig();
        assertTrue(validConfig.isValid());

        // Invalid - null table name
        TestConfiguration invalidConfig1 = createValidConfig();
        invalidConfig1.setTableName(null);
        assertFalse(invalidConfig1.isValid());

        // Invalid - empty table name
        TestConfiguration invalidConfig2 = createValidConfig();
        invalidConfig2.setTableName("");
        assertFalse(invalidConfig2.isValid());

        // Invalid - concurrency limit too low
        TestConfiguration invalidConfig3 = createValidConfig();
        invalidConfig3.setConcurrencyLimit(0);
        assertFalse(invalidConfig3.isValid());

        // Invalid - total items too low
        TestConfiguration invalidConfig4 = createValidConfig();
        invalidConfig4.setTotalItems(0);
        assertFalse(invalidConfig4.isValid());

        // Invalid - percentage too low
        TestConfiguration invalidConfig5 = createValidConfig();
        invalidConfig5.setMaxConcurrencyPercentage(0.05);
        assertFalse(invalidConfig5.isValid());

        // Invalid - percentage too high
        TestConfiguration invalidConfig6 = createValidConfig();
        invalidConfig6.setMaxConcurrencyPercentage(100.1);
        assertFalse(invalidConfig6.isValid());

        // Invalid - null duplicate percentage
        TestConfiguration invalidConfig7 = createValidConfig();
        invalidConfig7.setDuplicatePercentage(null);
        assertFalse(invalidConfig7.isValid());

        // Invalid - null environment
        TestConfiguration invalidConfig8 = createValidConfig();
        invalidConfig8.setEnvironment(null);
        assertFalse(invalidConfig8.isValid());
    }

    @Test
    @DisplayName("Should create safe copy correctly")
    void shouldCreateSafeCopyCorrectly() {
        TestConfiguration original = createValidConfig();
        TestConfiguration safeCopy = original.createSafeCopy();

        assertEquals(original, safeCopy);
        assertNotSame(original, safeCopy);

        // Modifying the safe copy should not affect the original
        safeCopy.setTableName("modified-table");
        assertNotEquals(original.getTableName(), safeCopy.getTableName());
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCodeCorrectly() {
        TestConfiguration config1 = createValidConfig();
        TestConfiguration config2 = createValidConfig();
        TestConfiguration config3 = createValidConfig();
        config3.setTableName("different-table");

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3);
        assertNotEquals(config1.hashCode(), config3.hashCode());
        assertNotEquals(config1, null);
        assertNotEquals(config1, "not a TestConfiguration");
    }

    @Test
    @DisplayName("Should provide meaningful toString representation")
    void shouldProvideMeaningfulToStringRepresentation() {
        TestConfiguration config = createValidConfig();
        String toString = config.toString();

        assertTrue(toString.contains("TestConfiguration"));
        assertTrue(toString.contains("test-table"));
        assertTrue(toString.contains("100"));
        assertTrue(toString.contains("10000"));
        assertTrue(toString.contains("50.0"));
        assertTrue(toString.contains("25.0"));
        assertTrue(toString.contains("local"));
        assertTrue(toString.contains("maxConcurrencyLevel"));
        assertTrue(toString.contains("itemsForMaxConcurrency"));
        assertTrue(toString.contains("itemsForRampUp"));
    }

    @Test
    @DisplayName("Should handle edge cases in calculations")
    void shouldHandleEdgeCasesInCalculations() {
        TestConfiguration config = new TestConfiguration();

        // Test with very small percentage
        config.setConcurrencyLimit(1000);
        config.setTotalItems(1000);
        config.setMaxConcurrencyPercentage(0.1);

        assertEquals(1, config.getMaxConcurrencyLevel()); // ceil(1000 * 0.001) = 1
        assertEquals(1, config.getItemsForMaxConcurrency()); // ceil(1000 * 0.001) = 1
        assertEquals(999, config.getItemsForRampUp()); // 1000 - 1

        // Test with 100% percentage
        config.setMaxConcurrencyPercentage(100.0);
        assertEquals(1000, config.getMaxConcurrencyLevel());
        assertEquals(1000, config.getItemsForMaxConcurrency());
        assertEquals(0, config.getItemsForRampUp());
    }

    private TestConfiguration createValidConfig() {
        return new TestConfiguration(
                "test-table",
                100,
                10000,
                50.0,
                25.0,
                true,
                "local");
    }
}