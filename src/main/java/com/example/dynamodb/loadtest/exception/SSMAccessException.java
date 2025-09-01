package com.example.dynamodb.loadtest.exception;

/**
 * Exception thrown when there's an error accessing AWS SSM Parameter Store.
 */
public class SSMAccessException extends RuntimeException {

    public SSMAccessException(String message) {
        super(message);
    }

    public SSMAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}