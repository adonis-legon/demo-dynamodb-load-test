package com.example.dynamodb.loadtest.repository;

import com.example.dynamodb.loadtest.exception.SSMAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SSMParameterRepositoryImplTest {

    @Mock
    private SsmAsyncClient ssmClient;

    private SSMParameterRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new SSMParameterRepositoryImpl(ssmClient);
    }

    @Test
    void getParameter_Success() {
        // Given
        String parameterName = "/test/parameter";
        String expectedValue = "test-value";

        Parameter parameter = Parameter.builder()
                .name(parameterName)
                .value(expectedValue)
                .build();

        GetParameterResponse response = GetParameterResponse.builder()
                .parameter(parameter)
                .build();

        when(ssmClient.getParameter(any(GetParameterRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        // When
        String result = repository.getParameter(parameterName).join();

        // Then
        assertThat(result).isEqualTo(expectedValue);

        verify(ssmClient)
                .getParameter(argThat((GetParameterRequest request) -> request.name().equals(parameterName)
                        && request.withDecryption()));
    }

    @Test
    void getParameter_ParameterNotFound() {
        // Given
        String parameterName = "/test/nonexistent";

        CompletableFuture<GetParameterResponse> failedFuture = new CompletableFuture<>();
        RuntimeException parameterNotFound = new RuntimeException("ParameterNotFound");
        failedFuture.completeExceptionally(parameterNotFound);

        when(ssmClient.getParameter(any(GetParameterRequest.class)))
                .thenReturn(failedFuture);

        // When & Then
        assertThatThrownBy(() -> repository.getParameter(parameterName).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SSMAccessException.class)
                .hasMessageContaining("Failed to retrieve parameter: " + parameterName);
    }

    @Test
    void getParametersByPath_Success_SinglePage() {
        // Given
        String path = "/test/app";

        Parameter param1 = Parameter.builder()
                .name("/test/app/param1")
                .value("value1")
                .build();

        Parameter param2 = Parameter.builder()
                .name("/test/app/param2")
                .value("value2")
                .build();

        GetParametersByPathResponse response = GetParametersByPathResponse.builder()
                .parameters(List.of(param1, param2))
                .nextToken(null)
                .build();

        when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        // When
        Map<String, String> result = repository.getParametersByPath(path).join();

        // Then
        assertThat(result).hasSize(2)
                .containsEntry("/test/app/param1", "value1")
                .containsEntry("/test/app/param2", "value2");

        verify(ssmClient)
                .getParametersByPath(argThat((GetParametersByPathRequest request) -> request.path().equals(path) &&
                        request.recursive() &&
                        request.withDecryption()));
    }

    @Test
    void getParametersByPath_EmptyResult() {
        // Given
        String path = "/test/empty";

        GetParametersByPathResponse response = GetParametersByPathResponse.builder()
                .parameters(List.of())
                .nextToken(null)
                .build();

        when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        // When
        Map<String, String> result = repository.getParametersByPath(path).join();

        // Then
        assertThat(result).isEmpty();
    }
}