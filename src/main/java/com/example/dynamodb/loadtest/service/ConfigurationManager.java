package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.exception.SSMAccessException;
import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.repository.SSMParameterRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service responsible for loading and validating configuration from SSM
 * Parameter Store.
 * Handles environment detection and parameter mapping to TestConfiguration
 * objects.
 */
@Component
public class ConfigurationManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);

    private final SSMParameterRepository ssmParameterRepository;
    private final Validator validator;
    private final String parameterPrefix;
    private final String environment;

    public ConfigurationManager(
            SSMParameterRepository ssmParameterRepository,
            Validator validator,
            @Value("${ssm.parameter-prefix:/local/dynamodb-load-test}") String parameterPrefix,
            @Value("${ENVIRONMENT:local}") String environment) {
        this.ssmParameterRepository = ssmParameterRepository;
        this.validator = validator;
        this.parameterPrefix = parameterPrefix;
        this.environment = environment;
    }

    /**
     * Loads configuration from SSM Parameter Store and validates it.
     * 
     * @return CompletableFuture containing the validated TestConfiguration
     * @throws SSMAccessException       if there's an error accessing SSM
     * @throws IllegalArgumentException if configuration validation fails
     */
    public CompletableFuture<TestConfiguration> loadConfiguration() {
        logger.info("Loading configuration from SSM Parameter Store with prefix: {}", parameterPrefix);

        return ssmParameterRepository.getParametersByPath(parameterPrefix)
                .thenApply(this::mapParametersToConfiguration)
                .thenApply(this::validateConfiguration)
                .whenComplete((config, throwable) -> {
                    if (throwable != null) {
                        logger.error("Failed to load configuration", throwable);
                    } else {
                        logger.info("Successfully loaded and validated configuration for environment: {}",
                                config.getEnvironment());
                    }
                });
    }

    /**
     * Checks if the current environment is a local development environment.
     * 
     * @return true if running in local environment
     */
    public boolean isLocalEnvironment() {
        return "local".equalsIgnoreCase(environment) ||
                environment.toLowerCase().contains("localhost") ||
                System.getenv("AWS_ENDPOINT_URL") != null;
    }

    /**
     * Gets the current environment name.
     * 
     * @return the environment name
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * Gets the SSM parameter prefix being used.
     * 
     * @return the parameter prefix
     */
    public String getParameterPrefix() {
        return parameterPrefix;
    }

    /**
     * Maps SSM parameters to TestConfiguration object.
     * 
     * @param parameters map of parameter names to values
     * @return TestConfiguration object
     * @throws IllegalArgumentException if required parameters are missing
     */
    private TestConfiguration mapParametersToConfiguration(Map<String, String> parameters) {
        logger.debug("Mapping {} parameters to configuration", parameters.size());

        TestConfiguration config = new TestConfiguration();

        // Set environment
        config.setEnvironment(environment);

        // Map parameters with proper error handling
        config.setTableName(getRequiredParameter(parameters, "table-name"));
        config.setConcurrencyLimit(parseIntegerParameter(parameters, "concurrency-limit"));
        config.setTotalItems(parseIntegerParameter(parameters, "total-items"));
        config.setMaxConcurrencyPercentage(parseDoubleParameter(parameters, "max-concurrency-percentage"));
        config.setDuplicatePercentage(parseDoubleParameter(parameters, "duplicate-percentage"));
        config.setCleanupAfterTest(parseBooleanParameter(parameters, "cleanup-after-test"));

        logger.debug("Mapped configuration: {}", config.createSafeCopy());
        return config;
    }

    /**
     * Validates the configuration using Bean Validation.
     * 
     * @param config the configuration to validate
     * @return the validated configuration
     * @throws IllegalArgumentException if validation fails
     */
    private TestConfiguration validateConfiguration(TestConfiguration config) {
        Set<ConstraintViolation<TestConfiguration>> violations = validator.validate(config);

        if (!violations.isEmpty()) {
            StringBuilder errorMessage = new StringBuilder("Configuration validation failed:");
            for (ConstraintViolation<TestConfiguration> violation : violations) {
                errorMessage.append("\n- ").append(violation.getPropertyPath())
                        .append(": ").append(violation.getMessage());
            }
            throw new IllegalArgumentException(errorMessage.toString());
        }

        // Additional logical validation
        if (!config.isValid()) {
            throw new IllegalArgumentException("Configuration failed logical validation checks");
        }

        logger.info("Configuration validation successful");
        return config;
    }

    /**
     * Gets a required parameter value, throwing exception if missing.
     * 
     * @param parameters    the parameter map
     * @param parameterName the parameter name (without prefix)
     * @return the parameter value
     * @throws IllegalArgumentException if parameter is missing
     */
    private String getRequiredParameter(Map<String, String> parameters, String parameterName) {
        String fullParameterName = parameterPrefix + "/" + parameterName;
        String value = parameters.get(fullParameterName);

        if (value == null || value.trim().isEmpty()) {
            // Try without prefix in case the map keys don't include the full path
            value = parameters.get(parameterName);
        }

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Required parameter not found: " + fullParameterName);
        }

        return value.trim();
    }

    /**
     * Parses an integer parameter.
     * 
     * @param parameters    the parameter map
     * @param parameterName the parameter name
     * @return the parsed integer value
     * @throws IllegalArgumentException if parameter is invalid
     */
    private Integer parseIntegerParameter(Map<String, String> parameters, String parameterName) {
        String value = getRequiredParameter(parameters, parameterName);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer parameter " + parameterName + ": " + value, e);
        }
    }

    /**
     * Parses a double parameter.
     * 
     * @param parameters    the parameter map
     * @param parameterName the parameter name
     * @return the parsed double value
     * @throws IllegalArgumentException if parameter is invalid
     */
    private Double parseDoubleParameter(Map<String, String> parameters, String parameterName) {
        String value = getRequiredParameter(parameters, parameterName);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid double parameter " + parameterName + ": " + value, e);
        }
    }

    /**
     * Parses a boolean parameter.
     * 
     * @param parameters    the parameter map
     * @param parameterName the parameter name
     * @return the parsed boolean value
     * @throws IllegalArgumentException if parameter is invalid
     */
    private Boolean parseBooleanParameter(Map<String, String> parameters, String parameterName) {
        String value = getRequiredParameter(parameters, parameterName);
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("Invalid boolean parameter " + parameterName + ": " + value +
                " (must be 'true' or 'false')");
    }
}