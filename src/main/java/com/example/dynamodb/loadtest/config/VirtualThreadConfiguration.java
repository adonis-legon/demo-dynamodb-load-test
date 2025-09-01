package com.example.dynamodb.loadtest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Configuration for Virtual Thread executor used in load testing.
 * Provides a Virtual Thread executor for high-concurrency operations.
 */
@Configuration
public class VirtualThreadConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(VirtualThreadConfiguration.class);

    /**
     * Creates a Virtual Thread executor for load testing operations.
     * 
     * @return Virtual Thread executor
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        logger.info("Creating Virtual Thread executor for load testing");
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}