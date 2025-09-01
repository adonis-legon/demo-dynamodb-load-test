package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service for estimating AWS costs associated with load testing.
 * Calculates costs for DynamoDB operations, ECS compute time, and data
 * transfer.
 */
@Service
public class CostEstimationService {

    private static final Logger logger = LoggerFactory.getLogger(CostEstimationService.class);

    // AWS Pricing (US East 1 - as of 2024, subject to change)
    private static final BigDecimal DYNAMODB_WRITE_COST_PER_MILLION = new BigDecimal("1.25"); // $1.25 per million
                                                                                              // writes
    private static final BigDecimal DYNAMODB_READ_COST_PER_MILLION = new BigDecimal("0.25"); // $0.25 per million reads
    private static final BigDecimal ECS_FARGATE_VCPU_COST_PER_HOUR = new BigDecimal("0.04048"); // $0.04048 per vCPU
                                                                                                // hour
    private static final BigDecimal ECS_FARGATE_MEMORY_COST_PER_GB_HOUR = new BigDecimal("0.004445"); // $0.004445 per
                                                                                                      // GB hour
    private static final BigDecimal DATA_TRANSFER_COST_PER_GB = new BigDecimal("0.09"); // $0.09 per GB (first 1GB free)

    @Value("${TASK_CPU:4096}")
    private int taskCpu; // CPU units (1024 = 1 vCPU)

    @Value("${TASK_MEMORY:8192}")
    private int taskMemory; // Memory in MB

    /**
     * Calculates comprehensive cost estimate for the load test.
     */
    public CostEstimate calculateCostEstimate(TestSummary summary) {
        logger.debug("Calculating cost estimate for load test");

        CostEstimate estimate = new CostEstimate();

        // DynamoDB costs
        estimate.dynamoDbWriteCost = calculateDynamoDbWriteCost(summary);
        estimate.dynamoDbReadCost = calculateDynamoDbReadCost(summary);
        estimate.dynamoDbTotalCost = estimate.dynamoDbWriteCost.add(estimate.dynamoDbReadCost);

        // ECS costs
        estimate.ecsComputeCost = calculateEcsComputeCost(summary);

        // Data transfer costs (minimal for this use case)
        estimate.dataTransferCost = calculateDataTransferCost(summary);

        // Total cost
        estimate.totalCost = estimate.dynamoDbTotalCost
                .add(estimate.ecsComputeCost)
                .add(estimate.dataTransferCost);

        // Cost per operation
        if (summary.getTotalOperations() > 0) {
            estimate.costPerOperation = estimate.totalCost
                    .divide(new BigDecimal(summary.getTotalOperations()), 6, RoundingMode.HALF_UP);
        }

        // Cost per hour (extrapolated)
        if (summary.getTestDuration() != null && !summary.getTestDuration().isZero()) {
            double hoursInTest = summary.getTestDuration().toMillis() / (1000.0 * 3600.0);
            estimate.costPerHour = estimate.totalCost
                    .divide(new BigDecimal(hoursInTest), 4, RoundingMode.HALF_UP);
        }

        logger.debug("Cost estimate calculated: Total=${}", estimate.totalCost);
        return estimate;
    }

    private BigDecimal calculateDynamoDbWriteCost(TestSummary summary) {
        // Each successful operation is a write to DynamoDB
        long writeOperations = summary.getTotalSuccesses();

        return DYNAMODB_WRITE_COST_PER_MILLION
                .multiply(new BigDecimal(writeOperations))
                .divide(new BigDecimal(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateDynamoDbReadCost(TestSummary summary) {
        // Minimal reads for table existence checks, etc.
        // Estimate ~1 read per 1000 writes for metadata operations
        long estimatedReads = Math.max(1, summary.getTotalOperations() / 1000);

        return DYNAMODB_READ_COST_PER_MILLION
                .multiply(new BigDecimal(estimatedReads))
                .divide(new BigDecimal(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateEcsComputeCost(TestSummary summary) {
        if (summary.getTestDuration() == null) {
            return BigDecimal.ZERO;
        }

        // Convert duration to hours
        double hours = summary.getTestDuration().toMillis() / (1000.0 * 3600.0);

        // Calculate vCPU cost
        double vCpus = taskCpu / 1024.0; // Convert CPU units to vCPUs
        BigDecimal cpuCost = ECS_FARGATE_VCPU_COST_PER_HOUR
                .multiply(new BigDecimal(vCpus))
                .multiply(new BigDecimal(hours));

        // Calculate memory cost
        double memoryGb = taskMemory / 1024.0; // Convert MB to GB
        BigDecimal memoryCost = ECS_FARGATE_MEMORY_COST_PER_GB_HOUR
                .multiply(new BigDecimal(memoryGb))
                .multiply(new BigDecimal(hours));

        return cpuCost.add(memoryCost);
    }

    private BigDecimal calculateDataTransferCost(TestSummary summary) {
        // Estimate data transfer based on operations
        // Each DynamoDB operation transfers ~1KB on average
        long totalBytes = summary.getTotalOperations() * 1024; // 1KB per operation
        double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);

        // First 1GB is free, so subtract if applicable
        double billableGb = Math.max(0, totalGb - 1.0);

        return DATA_TRANSFER_COST_PER_GB.multiply(new BigDecimal(billableGb));
    }

    /**
     * Cost estimate breakdown for a load test.
     */
    public static class CostEstimate {
        public BigDecimal dynamoDbWriteCost = BigDecimal.ZERO;
        public BigDecimal dynamoDbReadCost = BigDecimal.ZERO;
        public BigDecimal dynamoDbTotalCost = BigDecimal.ZERO;
        public BigDecimal ecsComputeCost = BigDecimal.ZERO;
        public BigDecimal dataTransferCost = BigDecimal.ZERO;
        public BigDecimal totalCost = BigDecimal.ZERO;
        public BigDecimal costPerOperation = BigDecimal.ZERO;
        public BigDecimal costPerHour = BigDecimal.ZERO;

        public String formatCurrency(BigDecimal amount) {
            if (amount.compareTo(new BigDecimal("0.01")) < 0) {
                return String.format("$%.4f", amount);
            } else {
                return String.format("$%.2f", amount);
            }
        }
    }
}