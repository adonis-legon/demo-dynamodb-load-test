package com.example.dynamodb.loadtest.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for LocalStack integration tests.
 * Provides common setup for DynamoDB and SSM services with TestContainers.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Testcontainers
public abstract class LocalStackIntegrationTestBase {

    protected static final String TEST_TABLE_NAME = "integration-test-table";
    protected static final String SSM_PARAMETER_PREFIX = "/integration-test/dynamodb-load-test";

    @Container
    protected static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0.2"))
            .withServices(
                    LocalStackContainer.Service.DYNAMODB,
                    LocalStackContainer.Service.SSM)
            .withEnv("DEBUG", "1")
            .withEnv("PERSISTENCE", "0")
            .withEnv("EAGER_SERVICE_LOADING", "1")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-url", () -> localstack.getEndpoint().toString());
        registry.add("aws.region", () -> localstack.getRegion());
        registry.add("dynamodb.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("ssm.endpoint", () -> localstack.getEndpoint().toString());

    }

    @Autowired
    protected DynamoDbAsyncClient dynamoDbClient;

    @Autowired
    protected SsmAsyncClient ssmClient;

    @BeforeAll
    static void checkDockerAvailability() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version");
            process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            assumeTrue(finished && process.exitValue() == 0, "Docker is not available");
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    @BeforeEach
    void setupTestEnvironment() {
        setupDynamoDBTable();
        setupSSMParameters();
    }

    /**
     * Creates the test DynamoDB table if it doesn't exist.
     */
    protected void setupDynamoDBTable() {
        try {
            // Check if table exists
            dynamoDbClient.describeTable(DescribeTableRequest.builder()
                    .tableName(TEST_TABLE_NAME)
                    .build()).join();
        } catch (Exception e) {
            // Table doesn't exist, create it
            CreateTableRequest createTableRequest = CreateTableRequest.builder()
                    .tableName(TEST_TABLE_NAME)
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("pk")
                            .keyType(KeyType.HASH)
                            .build())
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("pk")
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build();

            dynamoDbClient.createTable(createTableRequest).join();

            // Wait for table to be active
            waitForTableToBeActive(TEST_TABLE_NAME);
        }
    }

    /**
     * Sets up SSM parameters required for load testing.
     */
    protected void setupSSMParameters() {
        putSSMParameter("table-name", TEST_TABLE_NAME);
        putSSMParameter("concurrency-limit", "5");
        putSSMParameter("total-items", "50");
        putSSMParameter("max-concurrency-percentage", "80.0");
        putSSMParameter("duplicate-percentage", "20.0");
    }

    /**
     * Puts a parameter in SSM Parameter Store.
     */
    protected void putSSMParameter(String parameterName, String value) {
        String fullParameterName = SSM_PARAMETER_PREFIX + "/" + parameterName;
        ssmClient.putParameter(PutParameterRequest.builder()
                .name(fullParameterName)
                .value(value)
                .type(ParameterType.STRING)
                .overwrite(true)
                .build()).join();
    }

    /**
     * Waits for DynamoDB table to become active.
     */
    private void waitForTableToBeActive(String tableName) {
        int maxAttempts = 30;
        int attempt = 0;

        while (attempt < maxAttempts) {
            try {
                DescribeTableResponse response = dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build()).join();

                if (response.table().tableStatus() == TableStatus.ACTIVE) {
                    return;
                }

                Thread.sleep(1000);
                attempt++;
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxAttempts) {
                    throw new RuntimeException("Table did not become active within timeout", e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for table", ie);
                }
            }
        }

        throw new RuntimeException("Table did not become active within timeout");
    }

    /**
     * Cleans up test data from DynamoDB table.
     */
    protected void cleanupTestData() {
        try {
            // Scan and delete all items
            ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                    .tableName(TEST_TABLE_NAME)
                    .build()).join();

            for (var item : scanResponse.items()) {
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(TEST_TABLE_NAME)
                        .key(java.util.Map.of("pk", item.get("pk")))
                        .build()).join();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}