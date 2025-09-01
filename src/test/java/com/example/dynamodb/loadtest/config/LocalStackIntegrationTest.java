package com.example.dynamodb.loadtest.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for AWS client configuration with LocalStack.
 * Uses TestContainers to spin up LocalStack for testing AWS services.
 */
@SpringBootTest(classes = {
        AWSClientConfiguration.class,
        AWSConfigurationProperties.class
})
@ActiveProfiles("local")
@Testcontainers
class LocalStackIntegrationTest {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:2.3.2")) // Use a more stable version
            .withServices(
                    LocalStackContainer.Service.DYNAMODB,
                    LocalStackContainer.Service.SSM)
            .withEnv("DEBUG", "1")
            .withEnv("PERSISTENCE", "0") // Disable persistence for faster startup
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-url", () -> localstack.getEndpoint().toString());
        registry.add("aws.region", () -> localstack.getRegion());
        registry.add("dynamodb.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("ssm.endpoint", () -> localstack.getEndpoint().toString());
    }

    @Autowired
    private DynamoDbAsyncClient dynamoDbClient;

    @Autowired
    private SsmAsyncClient ssmClient;

    @Autowired
    private AWSConfigurationProperties awsProperties;

    @BeforeAll
    static void checkDockerAvailability() {
        // Skip tests if Docker is not available
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version");
            process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            assumeTrue(finished && process.exitValue() == 0, "Docker is not available");
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(30)
    void dynamoDbClientCanConnectToLocalStack() {
        // When
        var response = dynamoDbClient.listTables(ListTablesRequest.builder().build()).join();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.tableNames()).isNotNull();
    }

    @Test
    @Timeout(30)
    void ssmClientCanConnectToLocalStack() {
        // Given
        String parameterName = "/test/parameter";
        String parameterValue = "test-value";

        // Put a parameter first
        ssmClient.putParameter(PutParameterRequest.builder()
                .name(parameterName)
                .value(parameterValue)
                .type("String")
                .build()).join();

        // When
        var response = ssmClient.getParameter(GetParameterRequest.builder()
                .name(parameterName)
                .build()).join();

        // Then
        assertThat(response.parameter().value()).isEqualTo(parameterValue);
    }

    @Test
    @Timeout(30)
    void ssmClientHandlesParameterNotFound() {
        // Given
        String nonExistentParameter = "/test/nonexistent-" + System.currentTimeMillis();

        // When & Then
        assertThatThrownBy(() -> ssmClient.getParameter(GetParameterRequest.builder()
                .name(nonExistentParameter)
                .build()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ParameterNotFoundException.class);
    }

    @Test
    void awsPropertiesDetectLocalEnvironment() {
        // Then
        assertThat(awsProperties.isLocalEnvironment()).isTrue();
        assertThat(awsProperties.getEndpointUrl()).contains("localhost");
    }

    @Test
    @Timeout(30)
    void localStackContainerIsRunning() {
        // Verify LocalStack container is running
        assertThat(localstack.isRunning()).isTrue();
        assertThat(localstack.getEndpoint()).isNotNull();
        assertThat(localstack.getRegion()).isEqualTo("us-east-1");
    }
}