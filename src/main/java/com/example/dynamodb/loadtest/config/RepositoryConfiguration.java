package com.example.dynamodb.loadtest.config;

import com.example.dynamodb.loadtest.repository.DynamoDBRepository;
import com.example.dynamodb.loadtest.repository.DynamoDBRepositoryImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Configuration for repository beans.
 */
@Configuration
public class RepositoryConfiguration {

    @Value("${TABLE_NAME:load-test-table}")
    private String tableName;

    @Bean
    public DynamoDBRepository dynamoDBRepository(DynamoDbAsyncClient dynamoDbClient) {
        return new DynamoDBRepositoryImpl(dynamoDbClient, tableName);
    }
}