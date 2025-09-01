# Integration Tests

This directory contains comprehensive integration tests for the DynamoDB Load Test application using LocalStack.

## Overview

The integration tests use TestContainers to spin up LocalStack instances that provide local AWS service emulation for:

- DynamoDB
- SSM Parameter Store
- CloudWatch (basic support)

## Test Structure

### Base Class

- `LocalStackIntegrationTestBase`: Provides common setup for all integration tests including LocalStack container management, DynamoDB table creation, and SSM parameter setup.

### Test Classes

1. **LoadTestEndToEndIntegrationTest**: Tests the complete load test workflow from configuration loading to execution and metrics collection.

2. **ConfigurationLoadingIntegrationTest**: Tests configuration loading from SSM Parameter Store, including parameter validation and error handling.

3. **DynamoDBOperationsIntegrationTest**: Tests DynamoDB operations including item puts, concurrent operations, and error scenarios.

4. **MetricsCollectionIntegrationTest**: Tests metrics collection, aggregation, and reporting functionality.

5. **ApplicationLifecycleIntegrationTest**: Tests complete application startup and lifecycle with Spring Boot.

### Test Suite

- `IntegrationTestSuite`: Documentation class that lists all available integration tests.

## Running Integration Tests

### Prerequisites

- Docker must be installed and running
- Java 21 with preview features enabled

### Run All Integration Tests

```bash
mvn clean verify -P integration-tests
```

### Run Specific Integration Test

```bash
mvn test -Dtest=LoadTestEndToEndIntegrationTest
```

### Run Integration Test Suite

```bash
mvn test -Dtest=IntegrationTestSuite
```

## Configuration

### Test Configuration

- `application-integration.yml`: Test-specific configuration with LocalStack endpoints
- Test parameters are set up automatically in the base class

### LocalStack Configuration

- Uses LocalStack 3.0.2 with DynamoDB, SSM, and CloudWatch services
- Configured for fast startup with persistence disabled
- Debug logging enabled for troubleshooting

## Test Data Management

Each test class includes cleanup methods to ensure test isolation:

- DynamoDB table items are cleaned up after each test
- SSM parameters are reset to default values
- LocalStack container is shared across tests for performance

## Timeouts

All tests have appropriate timeouts:

- Simple operations: 30 seconds
- Load test execution: 1-2 minutes
- Application lifecycle: 3 minutes

## Troubleshooting

### Docker Issues

If tests fail with Docker-related errors:

1. Ensure Docker is running
2. Check Docker daemon accessibility
3. Verify sufficient memory allocation for Docker

### LocalStack Issues

If LocalStack fails to start:

1. Check Docker logs: `docker logs <container_id>`
2. Increase startup timeout if needed
3. Verify LocalStack image version compatibility

### Test Failures

Common issues and solutions:

1. **Timeout errors**: Increase test timeouts or check LocalStack performance
2. **Connection errors**: Verify LocalStack endpoints are correctly configured
3. **Resource conflicts**: Ensure proper test cleanup and isolation

## Coverage

The integration tests cover:

- ✅ Configuration loading from SSM
- ✅ DynamoDB operations and error handling
- ✅ Load test execution with various scenarios
- ✅ Metrics collection and reporting
- ✅ Concurrency control and Virtual Threads
- ✅ Error handling and resilience patterns
- ✅ Application lifecycle and Spring Boot integration

## Requirements Mapping

These integration tests fulfill the following requirements:

- **11.1**: Unit tests for all business logic components
- **11.2**: Integration tests for AWS service interactions
- **11.3**: Validation of load testing functionality correctness
- **11.4**: Configuration loading from SSM Parameter Store validation
- **11.5**: DynamoDB operations and error handling validation
