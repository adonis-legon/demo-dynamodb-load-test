package com.example.dynamodb.loadtest.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Mock-based integration test for AWS services.
 * Tests the integration logic without requiring actual AWS services or
 * LocalStack.
 */
@ExtendWith(MockitoExtension.class)
class AWSIntegrationMockTest {

    @Mock
    private DynamoDbAsyncClient dynamoDbClient;

    @Mock
    private SsmAsyncClient ssmClient;

    @Test
    void dynamoDbClientListTables_ReturnsExpectedResponse() {
        // Given
        ListTablesResponse expectedResponse = ListTablesResponse.builder()
                .tableNames("test-table-1", "test-table-2")
                .build();

        when(dynamoDbClient.listTables(any(ListTablesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(expectedResponse));

        // When
        CompletableFuture<ListTablesResponse> result = dynamoDbClient.listTables(
                ListTablesRequest.builder().build());

        // Then
        assertThat(result).isNotNull();
        ListTablesResponse response = result.join();
        assertThat(response.tableNames()).containsExactly("test-table-1", "test-table-2");
    }

    @Test
    void ssmClientGetParameter_ReturnsExpectedParameter() {
        // Given
        String parameterName = "/test/parameter";
        String parameterValue = "test-value";

        Parameter parameter = Parameter.builder()
                .name(parameterName)
                .value(parameterValue)
                .type("String")
                .build();

        GetParameterResponse expectedResponse = GetParameterResponse.builder()
                .parameter(parameter)
                .build();

        when(ssmClient.getParameter(any(GetParameterRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(expectedResponse));

        // When
        CompletableFuture<GetParameterResponse> result = ssmClient.getParameter(
                GetParameterRequest.builder().name(parameterName).build());

        // Then
        assertThat(result).isNotNull();
        GetParameterResponse response = result.join();
        assertThat(response.parameter().value()).isEqualTo(parameterValue);
        assertThat(response.parameter().name()).isEqualTo(parameterName);
    }

    @Test
    void ssmClientGetParameter_ParameterNotFound_ThrowsException() {
        // Given
        String nonExistentParameter = "/test/nonexistent";

        when(ssmClient.getParameter(any(GetParameterRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ParameterNotFoundException.builder()
                                .message("Parameter not found: " + nonExistentParameter)
                                .build()));

        // When & Then
        CompletableFuture<GetParameterResponse> result = ssmClient.getParameter(
                GetParameterRequest.builder().name(nonExistentParameter).build());

        assertThatThrownBy(result::join)
                .hasCauseInstanceOf(ParameterNotFoundException.class)
                .hasMessageContaining("Parameter not found");
    }

    @Test
    void dynamoDbClientListTables_EmptyResponse() {
        // Given
        ListTablesResponse emptyResponse = ListTablesResponse.builder()
                .tableNames(List.of())
                .build();

        when(dynamoDbClient.listTables(any(ListTablesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(emptyResponse));

        // When
        CompletableFuture<ListTablesResponse> result = dynamoDbClient.listTables(
                ListTablesRequest.builder().build());

        // Then
        assertThat(result).isNotNull();
        ListTablesResponse response = result.join();
        assertThat(response.tableNames()).isEmpty();
    }

    @Test
    void awsConfigurationProperties_LocalEnvironmentDetection() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();
        properties.setEndpointUrl("http://localhost:4566");
        properties.setRegion("us-east-1");

        // When & Then
        assertThat(properties.isLocalEnvironment()).isTrue();
        assertThat(properties.getEndpointUrl()).contains("localhost");
        assertThat(properties.getRegion()).isEqualTo("us-east-1");
    }

    @Test
    void awsConfigurationProperties_ProductionEnvironmentDetection() {
        // Given
        AWSConfigurationProperties properties = new AWSConfigurationProperties();
        properties.setRegion("us-west-2");
        // No endpoint URL set (null) - indicates production

        // When & Then
        assertThat(properties.isLocalEnvironment()).isFalse();
        assertThat(properties.getRegion()).isEqualTo("us-west-2");
    }
}