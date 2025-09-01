package com.example.dynamodb.loadtest.repository;

import com.example.dynamodb.loadtest.exception.ParameterNotFoundException;
import com.example.dynamodb.loadtest.exception.SSMAccessException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Repository interface for AWS Systems Manager Parameter Store operations.
 * Provides async methods for retrieving configuration parameters.
 */
public interface SSMParameterRepository {

    /**
     * Retrieves a single parameter value from SSM Parameter Store.
     * 
     * @param parameterName the name of the parameter to retrieve
     * @return CompletableFuture containing the parameter value
     * @throws ParameterNotFoundException if the parameter doesn't exist
     * @throws SSMAccessException         if there's an error accessing SSM
     */
    CompletableFuture<String> getParameter(String parameterName);

    /**
     * Retrieves multiple parameters by path prefix from SSM Parameter Store.
     * 
     * @param path the path prefix to search for parameters
     * @return CompletableFuture containing a map of parameter names to values
     * @throws SSMAccessException if there's an error accessing SSM
     */
    CompletableFuture<Map<String, String>> getParametersByPath(String path);
}