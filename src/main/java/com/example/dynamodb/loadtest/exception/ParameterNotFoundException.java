package com.example.dynamodb.loadtest.exception;

/**
 * Exception thrown when a requested SSM parameter is not found.
 */
public class ParameterNotFoundException extends RuntimeException {

    public ParameterNotFoundException(String parameterName) {
        super("Parameter not found: " + parameterName);
    }

    public ParameterNotFoundException(String parameterName, Throwable cause) {
        super("Parameter not found: " + parameterName, cause);
    }
}