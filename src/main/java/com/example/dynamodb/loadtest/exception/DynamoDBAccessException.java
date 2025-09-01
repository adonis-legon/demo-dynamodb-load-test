package com.example.dynamodb.loadtest.exception;

/**
 * Exception thrown when there's an error accessing DynamoDB.
 */
public class DynamoDBAccessException extends RuntimeException {

    public DynamoDBAccessException(String message) {
        super(message);
    }

    public DynamoDBAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}