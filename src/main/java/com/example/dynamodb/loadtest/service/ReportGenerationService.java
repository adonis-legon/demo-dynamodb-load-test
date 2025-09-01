package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import com.example.dynamodb.loadtest.service.CostEstimationService.CostEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Service for generating formatted reports of load test results.
 * Provides stdout report generation with response times, error rates, and
 * detailed metrics.
 */
@Service
public class ReportGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(ReportGenerationService.class);

    private static final String SEPARATOR = "=".repeat(80);
    private static final String SUB_SEPARATOR = "-".repeat(60);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private CostEstimationService costEstimationService;

    /**
     * Generates and prints a comprehensive test report to stdout.
     * 
     * @param summary the test summary containing all metrics
     */
    public void generateReport(TestSummary summary) {
        generateReport(summary, System.out);
    }

    /**
     * Generates and prints a comprehensive test report to the specified
     * PrintStream.
     * 
     * @param summary the test summary containing all metrics
     * @param out     the PrintStream to write the report to
     */
    public void generateReport(TestSummary summary, PrintStream out) {
        if (summary == null) {
            logger.warn("Cannot generate report: summary is null");
            out.println("Error: No test summary available for report generation");
            return;
        }

        logger.info("Generating load test report");

        try {
            printHeader(out);
            printTestOverview(summary, out);
            printPerformanceMetrics(summary, out);
            printCostAnalysis(summary, out);
            printErrorAnalysis(summary, out);
            printConcurrencyAnalysis(summary, out);
            printFooter(out);

            logger.info("Load test report generated successfully");
        } catch (Exception e) {
            logger.error("Error generating report", e);
            out.println("Error generating report: " + e.getMessage());
        }
    }

    /**
     * Generates a summary report with key metrics only.
     * 
     * @param summary the test summary
     * @return formatted summary string
     */
    public String generateSummaryReport(TestSummary summary) {
        if (summary == null) {
            return "No test summary available";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Load Test Summary:\n");
        sb.append(String.format("  Total Operations: %,d\n", summary.getTotalOperations()));
        sb.append(String.format("  Success Rate: %.2f%%\n", summary.getSuccessRate()));
        sb.append(String.format("  Error Rate: %.2f%%\n", summary.getErrorRate()));
        sb.append(String.format("  Average Response Time: %dms\n", summary.getAverageResponseTime().toMillis()));
        sb.append(String.format("  Throughput: %.2f ops/sec\n", summary.getThroughputPerSecond()));
        sb.append(String.format("  Test Duration: %s\n", formatDuration(summary.getTestDuration())));

        // Add cost information
        try {
            CostEstimate estimate = costEstimationService.calculateCostEstimate(summary);
            sb.append(String.format("  Estimated Cost: %s\n", estimate.formatCurrency(estimate.totalCost)));
            sb.append(String.format("  Cost per Operation: %s\n", estimate.formatCurrency(estimate.costPerOperation)));
        } catch (Exception e) {
            sb.append("  Cost estimation unavailable\n");
        }

        return sb.toString();
    }

    private void printHeader(PrintStream out) {
        out.println(SEPARATOR);
        out.println("                    DYNAMODB LOAD TEST REPORT");
        out.println(SEPARATOR);
        out.println();
    }

    private void printTestOverview(TestSummary summary, PrintStream out) {
        out.println("TEST OVERVIEW");
        out.println(SUB_SEPARATOR);

        if (summary.getTestStartTime() != null) {
            out.printf("Start Time:        %s%n",
                    summary.getTestStartTime().atZone(java.time.ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER));
        }

        if (summary.getTestEndTime() != null) {
            out.printf("End Time:          %s%n",
                    summary.getTestEndTime().atZone(java.time.ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER));
        }

        out.printf("Duration:          %s%n", formatDuration(summary.getTestDuration()));
        out.printf("Total Operations:  %,d%n", summary.getTotalOperations());
        out.printf("Successful Ops:    %,d%n", summary.getTotalSuccesses());
        out.printf("Failed Ops:        %,d%n", summary.getTotalErrors());
        out.println();
    }

    private void printPerformanceMetrics(TestSummary summary, PrintStream out) {
        out.println("PERFORMANCE METRICS");
        out.println(SUB_SEPARATOR);

        out.printf("Success Rate:      %.2f%%%n", summary.getSuccessRate());
        out.printf("Error Rate:        %.2f%%%n", summary.getErrorRate());
        out.printf("Throughput:        %.2f operations/second%n", summary.getThroughputPerSecond());
        out.println();

        out.println("Response Time Statistics:");
        out.printf("  Average:         %dms%n", summary.getAverageResponseTime().toMillis());

        Map<String, Duration> percentiles = summary.getResponseTimePercentiles();
        if (!percentiles.isEmpty()) {
            percentiles.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.printf("  %s:             %dms%n",
                            entry.getKey().toUpperCase(), entry.getValue().toMillis()));
        }
        out.println();
    }

    private void printCostAnalysis(TestSummary summary, PrintStream out) {
        out.println("COST ANALYSIS");
        out.println(SUB_SEPARATOR);

        try {
            CostEstimate estimate = costEstimationService.calculateCostEstimate(summary);

            out.println("AWS Service Costs (US East 1 pricing):");
            out.printf("  DynamoDB Writes:       %s (%,d operations)%n",
                    estimate.formatCurrency(estimate.dynamoDbWriteCost), summary.getTotalSuccesses());
            out.printf("  DynamoDB Reads:        %s (metadata operations)%n",
                    estimate.formatCurrency(estimate.dynamoDbReadCost));
            out.printf("  ECS Fargate Compute:   %s (%.1f vCPU, %.1fGB RAM)%n",
                    estimate.formatCurrency(estimate.ecsComputeCost),
                    Integer.parseInt(System.getProperty("TASK_CPU", "4096")) / 1024.0,
                    Integer.parseInt(System.getProperty("TASK_MEMORY", "8192")) / 1024.0);
            out.printf("  Data Transfer:         %s%n",
                    estimate.formatCurrency(estimate.dataTransferCost));
            out.println("  " + SUB_SEPARATOR.substring(0, 40));
            out.printf("  Total Test Cost:       %s%n",
                    estimate.formatCurrency(estimate.totalCost));
            out.println();

            out.println("Cost Efficiency Metrics:");
            out.printf("  Cost per Operation:    %s%n",
                    estimate.formatCurrency(estimate.costPerOperation));

            if (estimate.costPerHour.compareTo(BigDecimal.ZERO) > 0) {
                out.printf("  Extrapolated Cost/Hour: %s%n",
                        estimate.formatCurrency(estimate.costPerHour));
            }

            // Cost breakdown percentages
            if (estimate.totalCost.compareTo(BigDecimal.ZERO) > 0) {
                out.println();
                out.println("Cost Breakdown:");
                double dynamoDbPercent = estimate.dynamoDbTotalCost
                        .divide(estimate.totalCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).doubleValue();
                double ecsPercent = estimate.ecsComputeCost
                        .divide(estimate.totalCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).doubleValue();

                out.printf("  DynamoDB:              %.1f%%  (primary cost driver)%n", dynamoDbPercent);
                out.printf("  ECS Compute:           %.1f%%  (infrastructure cost)%n", ecsPercent);

                if (estimate.dataTransferCost.compareTo(BigDecimal.ZERO) > 0) {
                    double transferPercent = estimate.dataTransferCost
                            .divide(estimate.totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100)).doubleValue();
                    out.printf("  Data Transfer:         %.1f%%  (network cost)%n", transferPercent);
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to calculate cost estimate", e);
            out.println("Cost estimation unavailable due to configuration error.");
        }

        out.println();
        out.println("Note: Costs are estimates based on AWS US East 1 pricing and may vary.");
        out.println("Actual costs depend on your AWS pricing tier and region.");
        out.println();
    }

    private void printErrorAnalysis(TestSummary summary, PrintStream out) {
        out.println("ERROR ANALYSIS");
        out.println(SUB_SEPARATOR);

        Map<String, Long> errorCounts = summary.getErrorTypeCounts();

        if (errorCounts.isEmpty()) {
            out.println("No errors occurred during the test.");
        } else {
            out.printf("Total Errors:      %,d%n", summary.getTotalErrors());
            out.println();
            out.println("Error Breakdown:");

            errorCounts.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())) // Sort by count descending
                    .forEach(entry -> {
                        double percentage = (entry.getValue() * 100.0) / summary.getTotalOperations();
                        out.printf("  %-20s %,8d (%5.2f%%)%n",
                                formatErrorType(entry.getKey()) + ":", entry.getValue(), percentage);
                    });

            // Highlight critical error types
            printCriticalErrors(summary, out);
        }
        out.println();
    }

    private void printCriticalErrors(TestSummary summary, PrintStream out) {
        Map<String, Long> errorCounts = summary.getErrorTypeCounts();

        long capacityErrors = errorCounts.getOrDefault(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, 0L);
        long duplicateErrors = errorCounts.getOrDefault(TestMetrics.ERROR_TYPE_DUPLICATE_KEY, 0L);
        long throttlingErrors = errorCounts.getOrDefault(TestMetrics.ERROR_TYPE_THROTTLING, 0L);

        if (capacityErrors > 0 || duplicateErrors > 0 || throttlingErrors > 0) {
            out.println();
            out.println("Critical Error Summary:");

            if (capacityErrors > 0) {
                double percentage = (capacityErrors * 100.0) / summary.getTotalOperations();
                out.printf("  Capacity Limit Errors:    %,d (%.2f%%) - Consider increasing table capacity%n",
                        capacityErrors, percentage);
            }

            if (throttlingErrors > 0) {
                double percentage = (throttlingErrors * 100.0) / summary.getTotalOperations();
                out.printf("  Throttling Errors:        %,d (%.2f%%) - Reduce request rate%n",
                        throttlingErrors, percentage);
            }

            if (duplicateErrors > 0) {
                double percentage = (duplicateErrors * 100.0) / summary.getTotalOperations();
                out.printf("  Duplicate Key Errors:     %,d (%.2f%%) - Expected if duplicate injection enabled%n",
                        duplicateErrors, percentage);
            }
        }
    }

    private void printConcurrencyAnalysis(TestSummary summary, PrintStream out) {
        out.println("CONCURRENCY ANALYSIS");
        out.println(SUB_SEPARATOR);

        Map<Integer, TestMetrics> concurrencyMetrics = summary.getConcurrencyLevelMetrics();

        if (concurrencyMetrics.isEmpty()) {
            out.println("No concurrency-level metrics available.");
        } else {
            out.printf("%-12s %-12s %-12s %-12s %-15s%n",
                    "Concurrency", "Operations", "Success Rate", "Avg Resp Time", "Throughput");
            out.println(SUB_SEPARATOR);

            concurrencyMetrics.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        int level = entry.getKey();
                        TestMetrics metrics = entry.getValue();

                        out.printf("%-12d %-12d %-12.2f%% %-12dms %-15.2f%n",
                                level,
                                metrics.getTotalOperations(),
                                metrics.getSuccessRate(),
                                (long) metrics.getAverageResponseTimeMs(),
                                metrics.getThroughputPerSecond());
                    });
        }
        out.println();
    }

    private void printFooter(PrintStream out) {
        out.println(SEPARATOR);
        out.println("Report generated at: " +
                java.time.LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        out.println(SEPARATOR);
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "Unknown";
        }

        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long millis = duration.toMillisPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else if (seconds > 0) {
            return String.format("%d.%03ds", seconds, millis);
        } else {
            return String.format("%dms", duration.toMillis());
        }
    }

    private String formatErrorType(String errorType) {
        if (errorType == null) {
            return "Unknown";
        }

        // Convert camelCase or snake_case to readable format
        String formatted = errorType.replaceAll("([A-Z])", " $1")
                .replaceAll("_", " ")
                .trim()
                .toLowerCase();

        // Capitalize first letter of each word
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;

        for (char c : formatted.toCharArray()) {
            if (Character.isWhitespace(c)) {
                result.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }
}