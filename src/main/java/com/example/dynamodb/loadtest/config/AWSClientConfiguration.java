package com.example.dynamodb.loadtest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import java.time.Duration;

import java.net.URI;

/**
 * Configuration class for AWS SDK clients.
 * Provides different configurations for local (LocalStack) and AWS
 * environments.
 */
@Configuration
public class AWSClientConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AWSClientConfiguration.class);

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Configuration for local development with LocalStack.
     */
    @Configuration
    @Profile("local")
    static class LocalStackConfiguration {

        @Value("${aws.endpoint-url:http://localhost:4566}")
        private String endpointUrl;

        @Value("${aws.region:us-east-1}")
        private String awsRegion;

        @Bean
        public DynamoDbAsyncClient dynamoDbAsyncClient() {
            logger.info("Creating DynamoDB client for LocalStack at endpoint: {}", endpointUrl);

            return DynamoDbAsyncClient.builder()
                    .region(Region.of(awsRegion))
                    .endpointOverride(URI.create(endpointUrl))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        }

        @Bean
        public SsmAsyncClient ssmAsyncClient() {
            logger.info("Creating SSM client for LocalStack at endpoint: {}", endpointUrl);

            return SsmAsyncClient.builder()
                    .region(Region.of(awsRegion))
                    .endpointOverride(URI.create(endpointUrl))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        }

    }

    /**
     * Configuration for AWS cloud environment.
     */
    @Configuration
    @Profile("aws")
    static class AWSCloudConfiguration {

        @Value("${aws.region:us-east-1}")
        private String awsRegion;

        @Bean
        public DynamoDbAsyncClient dynamoDbAsyncClient() {
            logger.info("Creating optimized DynamoDB client for AWS region: {}", awsRegion);

            return DynamoDbAsyncClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .overrideConfiguration(builder -> builder
                            .apiCallTimeout(Duration.ofSeconds(30))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))
                            .retryPolicy(retryPolicyBuilder -> retryPolicyBuilder
                                    .numRetries(3)
                                    .build()))
                    .build();
        }

        @Bean
        public SsmAsyncClient ssmAsyncClient() {
            logger.info("Creating SSM client for AWS region: {}", awsRegion);

            return SsmAsyncClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        }

    }
}