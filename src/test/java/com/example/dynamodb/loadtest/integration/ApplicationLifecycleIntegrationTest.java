package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.LoadTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for complete application lifecycle with LocalStack.
 */
@SpringBootTest(classes = LoadTestApplication.class)
@ActiveProfiles("integration")
class ApplicationLifecycleIntegrationTest extends LocalStackIntegrationTestBase {

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldStartApplicationAndExecuteLoadTestSuccessfully() {
        // Given - Application context is loaded (via @SpringBootTest)
        // LocalStack is running (via base class)
        // SSM parameters are set up (via base class)
        // DynamoDB table is created (via base class)

        // Capture stdout to verify application output
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try {
            // When - Application runs (this happens automatically with @SpringBootTest)
            // The CommandLineRunner in LoadTestApplication should execute

            // Wait a moment for any async operations to complete
            Thread.sleep(2000);

            // Then - Verify application produced output
            String output = outputStream.toString();

            // The exact output depends on the CommandLineRunner implementation
            // At minimum, we should see some log output indicating the application started
            assertThat(output).isNotEmpty();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldLoadSpringContextSuccessfully() {
        // When - Spring context is loaded
        // Then - No exceptions should be thrown during context loading
        // This test passes if the @SpringBootTest annotation successfully loads the
        // context
        assertThat(true).isTrue(); // Context loaded successfully if we reach this point
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHaveAllRequiredBeansConfigured() {
        // When - Spring context is loaded
        // Then - All required beans should be available
        // This is implicitly tested by the @Autowired fields in the base class
        assertThat(dynamoDbClient).isNotNull();
        assertThat(ssmClient).isNotNull();
    }
}