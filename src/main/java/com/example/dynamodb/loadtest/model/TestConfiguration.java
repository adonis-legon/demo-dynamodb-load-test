package com.example.dynamodb.loadtest.model;

import jakarta.validation.constraints.*;
import java.util.Objects;

/**
 * Model representing the configuration for DynamoDB load testing.
 * Contains all parameters loaded from SSM Parameter Store.
 */
public class TestConfiguration {

    @NotBlank(message = "Table name cannot be blank")
    @Size(min = 3, max = 255, message = "Table name must be between 3 and 255 characters")
    private String tableName;

    @NotNull(message = "Concurrency limit cannot be null")
    @Min(value = 1, message = "Concurrency limit must be at least 1")
    @Max(value = 10000, message = "Concurrency limit cannot exceed 10000")
    private Integer concurrencyLimit;

    @NotNull(message = "Total items cannot be null")
    @Min(value = 1, message = "Total items must be at least 1")
    @Max(value = 10000000, message = "Total items cannot exceed 10 million")
    private Integer totalItems;

    @NotNull(message = "Max concurrency percentage cannot be null")
    @DecimalMin(value = "0.1", message = "Max concurrency percentage must be at least 0.1%")
    @DecimalMax(value = "100.0", message = "Max concurrency percentage cannot exceed 100%")
    private Double maxConcurrencyPercentage;

    @NotNull(message = "Duplicate percentage cannot be null")
    @DecimalMin(value = "0.0", message = "Duplicate percentage must be between 0% and 100%")
    @DecimalMax(value = "100.0", message = "Duplicate percentage must be between 0% and 100%")
    private Double duplicatePercentage;

    @NotNull(message = "Cleanup after test flag cannot be null")
    private Boolean cleanupAfterTest;

    @NotBlank(message = "Environment cannot be blank")
    @Pattern(regexp = "^(local|dev|test|staging|prod|aws)$", message = "Environment must be one of: local, dev, test, staging, prod, aws")
    private String environment;

    // Default constructor
    public TestConfiguration() {
    }

    // Full constructor
    public TestConfiguration(String tableName, Integer concurrencyLimit, Integer totalItems,
            Double maxConcurrencyPercentage, Double duplicatePercentage, Boolean cleanupAfterTest, String environment) {
        this.tableName = tableName;
        this.concurrencyLimit = concurrencyLimit;
        this.totalItems = totalItems;
        this.maxConcurrencyPercentage = maxConcurrencyPercentage;
        this.duplicatePercentage = duplicatePercentage;
        this.cleanupAfterTest = cleanupAfterTest;
        this.environment = environment;
    }

    // Getters and Setters
    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Integer getConcurrencyLimit() {
        return concurrencyLimit;
    }

    public void setConcurrencyLimit(Integer concurrencyLimit) {
        this.concurrencyLimit = concurrencyLimit;
    }

    public Integer getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(Integer totalItems) {
        this.totalItems = totalItems;
    }

    public Double getMaxConcurrencyPercentage() {
        return maxConcurrencyPercentage;
    }

    public void setMaxConcurrencyPercentage(Double maxConcurrencyPercentage) {
        this.maxConcurrencyPercentage = maxConcurrencyPercentage;
    }

    public Double getDuplicatePercentage() {
        return duplicatePercentage;
    }

    public void setDuplicatePercentage(Double duplicatePercentage) {
        this.duplicatePercentage = duplicatePercentage;
    }

    public Boolean getCleanupAfterTest() {
        return cleanupAfterTest;
    }

    public void setCleanupAfterTest(Boolean cleanupAfterTest) {
        this.cleanupAfterTest = cleanupAfterTest;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    // Derived properties and validation methods

    /**
     * Calculates the maximum concurrency level based on the percentage.
     * 
     * @return the calculated max concurrency level
     */
    public int getMaxConcurrencyLevel() {
        if (concurrencyLimit == null || maxConcurrencyPercentage == null) {
            return 0;
        }
        return (int) Math.ceil(concurrencyLimit * (maxConcurrencyPercentage / 100.0));
    }

    /**
     * Calculates the number of items to be written at maximum concurrency.
     * 
     * @return the number of items for max concurrency
     */
    public int getItemsForMaxConcurrency() {
        if (totalItems == null || maxConcurrencyPercentage == null) {
            return 0;
        }
        return (int) Math.ceil(totalItems * (maxConcurrencyPercentage / 100.0));
    }

    /**
     * Calculates the number of items to be written at lower concurrency levels.
     * 
     * @return the number of items for ramping up concurrency
     */
    public int getItemsForRampUp() {
        return totalItems != null ? totalItems - getItemsForMaxConcurrency() : 0;
    }

    /**
     * Checks if this is a local environment configuration.
     * 
     * @return true if environment is local
     */
    public boolean isLocalEnvironment() {
        return "local".equalsIgnoreCase(environment);
    }

    /**
     * Checks if this is a production environment configuration.
     * 
     * @return true if environment is prod
     */
    public boolean isProductionEnvironment() {
        return "prod".equalsIgnoreCase(environment);
    }

    /**
     * Validates the configuration for logical consistency.
     * 
     * @return true if configuration is logically valid
     */
    public boolean isValid() {
        if (tableName == null || tableName.trim().isEmpty() ||
                concurrencyLimit == null || concurrencyLimit < 1 ||
                totalItems == null || totalItems < 1 ||
                maxConcurrencyPercentage == null || maxConcurrencyPercentage < 0.1 || maxConcurrencyPercentage > 100.0
                ||
                duplicatePercentage == null || duplicatePercentage < 0.0 || duplicatePercentage > 100.0
                ||
                cleanupAfterTest == null ||
                environment == null || environment.trim().isEmpty()) {
            return false;
        }

        // Additional logical validations
        return getMaxConcurrencyLevel() >= 1 && getItemsForMaxConcurrency() >= 1;
    }

    /**
     * Creates a copy of this configuration with sensitive information masked.
     * 
     * @return a safe copy for logging
     */
    public TestConfiguration createSafeCopy() {
        TestConfiguration safeCopy = new TestConfiguration();
        safeCopy.tableName = this.tableName;
        safeCopy.concurrencyLimit = this.concurrencyLimit;
        safeCopy.totalItems = this.totalItems;
        safeCopy.maxConcurrencyPercentage = this.maxConcurrencyPercentage;
        safeCopy.duplicatePercentage = this.duplicatePercentage;
        safeCopy.cleanupAfterTest = this.cleanupAfterTest;
        safeCopy.environment = this.environment;
        return safeCopy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TestConfiguration that = (TestConfiguration) o;
        return Objects.equals(tableName, that.tableName) &&
                Objects.equals(concurrencyLimit, that.concurrencyLimit) &&
                Objects.equals(totalItems, that.totalItems) &&
                Objects.equals(maxConcurrencyPercentage, that.maxConcurrencyPercentage) &&
                Objects.equals(duplicatePercentage, that.duplicatePercentage) &&
                Objects.equals(cleanupAfterTest, that.cleanupAfterTest) &&
                Objects.equals(environment, that.environment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, concurrencyLimit, totalItems,
                maxConcurrencyPercentage, duplicatePercentage, cleanupAfterTest, environment);
    }

    @Override
    public String toString() {
        return "TestConfiguration{" +
                "tableName='" + tableName + '\'' +
                ", concurrencyLimit=" + concurrencyLimit +
                ", totalItems=" + totalItems +
                ", maxConcurrencyPercentage=" + maxConcurrencyPercentage +
                ", duplicatePercentage=" + duplicatePercentage +
                ", cleanupAfterTest=" + cleanupAfterTest +
                ", environment='" + environment + '\'' +
                ", maxConcurrencyLevel=" + getMaxConcurrencyLevel() +
                ", itemsForMaxConcurrency=" + getItemsForMaxConcurrency() +
                ", itemsForRampUp=" + getItemsForRampUp() +
                '}';
    }
}