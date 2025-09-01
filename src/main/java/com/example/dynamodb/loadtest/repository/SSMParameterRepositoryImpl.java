package com.example.dynamodb.loadtest.repository;

import com.example.dynamodb.loadtest.exception.ParameterNotFoundException;
import com.example.dynamodb.loadtest.exception.SSMAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of SSMParameterRepository using AWS SDK v2.
 * Provides async operations for retrieving parameters from SSM Parameter Store.
 */
@Repository
public class SSMParameterRepositoryImpl implements SSMParameterRepository {

    private static final Logger logger = LoggerFactory.getLogger(SSMParameterRepositoryImpl.class);

    private final SsmAsyncClient ssmClient;

    public SSMParameterRepositoryImpl(SsmAsyncClient ssmClient) {
        this.ssmClient = ssmClient;
    }

    @Override
    public CompletableFuture<String> getParameter(String parameterName) {
        logger.debug("Retrieving parameter: {}", parameterName);

        GetParameterRequest request = GetParameterRequest.builder()
                .name(parameterName)
                .withDecryption(true)
                .build();

        return ssmClient.getParameter(request)
                .thenApply(response -> {
                    String value = response.parameter().value();
                    logger.debug("Successfully retrieved parameter: {}", parameterName);
                    return value;
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause();
                    if (cause != null && cause.getClass().getSimpleName().equals("ParameterNotFound")) {
                        logger.error("Parameter not found: {}", parameterName);
                        throw new ParameterNotFoundException(parameterName, cause);
                    } else {
                        logger.error("Error retrieving parameter: {}", parameterName, throwable);
                        throw new SSMAccessException("Failed to retrieve parameter: " + parameterName, throwable);
                    }
                });
    }

    @Override
    public CompletableFuture<Map<String, String>> getParametersByPath(String path) {
        logger.debug("Retrieving parameters by path: {}", path);

        return getParametersByPathRecursive(path, null, new HashMap<>());
    }

    /**
     * Recursively retrieves parameters by path, handling pagination.
     */
    private CompletableFuture<Map<String, String>> getParametersByPathRecursive(
            String path, String nextToken, Map<String, String> accumulator) {

        GetParametersByPathRequest.Builder requestBuilder = GetParametersByPathRequest.builder()
                .path(path)
                .recursive(true)
                .withDecryption(true)
                .maxResults(10); // AWS default, can be adjusted

        if (nextToken != null) {
            requestBuilder.nextToken(nextToken);
        }

        GetParametersByPathRequest request = requestBuilder.build();

        return ssmClient.getParametersByPath(request)
                .thenCompose(response -> {
                    // Add parameters from this page to accumulator
                    response.parameters().forEach(parameter -> {
                        String name = parameter.name();
                        String value = parameter.value();
                        accumulator.put(name, value);
                        logger.debug("Retrieved parameter: {} = {}", name,
                                value.length() > 50 ? value.substring(0, 50) + "..." : value);
                    });

                    // Check if there are more pages
                    if (response.nextToken() != null && !response.nextToken().isEmpty()) {
                        return getParametersByPathRecursive(path, response.nextToken(), accumulator);
                    } else {
                        logger.debug("Successfully retrieved {} parameters from path: {}",
                                accumulator.size(), path);
                        return CompletableFuture.completedFuture(accumulator);
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error retrieving parameters by path: {}", path, throwable);
                    throw new SSMAccessException("Failed to retrieve parameters by path: " + path, throwable);
                });
    }
}