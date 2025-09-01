package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Service for staging test requests to temporary files to avoid memory issues
 * with large datasets and prevent race conditions in duplicate generation.
 */
@Service
public class RequestStagingService {

    private static final Logger logger = LoggerFactory.getLogger(RequestStagingService.class);

    private final ObjectMapper objectMapper;
    private final Executor virtualThreadExecutor;

    // Threshold for using file staging (items)
    private static final int FILE_STAGING_THRESHOLD = 10000;

    // Batch size for file operations
    private static final int BATCH_SIZE = 1000;

    public RequestStagingService(ObjectMapper objectMapper, Executor virtualThreadExecutor) {
        this.objectMapper = objectMapper;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * Stages test items to a temporary file if the dataset is large.
     * 
     * @param items the items to stage
     * @return CompletableFuture with the path to the staged file, or null if no
     *         staging was needed
     */
    public CompletableFuture<Path> stageItemsIfNeeded(List<TestItem> items) {
        if (items.size() < FILE_STAGING_THRESHOLD) {
            logger.debug("Dataset size {} is below threshold {}, no staging needed",
                    items.size(), FILE_STAGING_THRESHOLD);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path tempFile = Files.createTempFile("dynamodb-load-test-", ".json");
                logger.info("Staging {} items to temporary file: {}", items.size(), tempFile);

                try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardOpenOption.WRITE)) {
                    writer.write("[\n");

                    for (int i = 0; i < items.size(); i++) {
                        TestItem item = items.get(i);
                        String json = objectMapper.writeValueAsString(item);
                        writer.write(json);

                        if (i < items.size() - 1) {
                            writer.write(",\n");
                        } else {
                            writer.write("\n");
                        }

                        // Flush periodically to avoid memory buildup
                        if (i % BATCH_SIZE == 0) {
                            writer.flush();
                        }
                    }

                    writer.write("]");
                }

                logger.info("Successfully staged {} items to file: {}", items.size(), tempFile);
                return tempFile;

            } catch (IOException e) {
                logger.error("Failed to stage items to file", e);
                throw new RuntimeException("Failed to stage items to file", e);
            }
        }, virtualThreadExecutor);
    }

    /**
     * Loads staged items from a temporary file in batches.
     * 
     * @param stagedFile     the path to the staged file
     * @param batchProcessor processor for each batch of items
     * @return CompletableFuture that completes when all batches are processed
     */
    public CompletableFuture<Void> processStagedItems(Path stagedFile, BatchProcessor batchProcessor) {
        if (stagedFile == null) {
            logger.debug("No staged file provided, skipping staged processing");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Processing staged items from file: {}", stagedFile);

                List<TestItem> allItems = loadItemsFromFile(stagedFile);

                // Process in batches to control memory usage
                for (int i = 0; i < allItems.size(); i += BATCH_SIZE) {
                    int endIndex = Math.min(i + BATCH_SIZE, allItems.size());
                    List<TestItem> batch = allItems.subList(i, endIndex);

                    logger.debug("Processing batch {}-{} of {} items", i, endIndex - 1, allItems.size());
                    batchProcessor.processBatch(batch);
                }

                logger.info("Completed processing {} staged items", allItems.size());

            } catch (Exception e) {
                logger.error("Failed to process staged items from file: {}", stagedFile, e);
                throw new RuntimeException("Failed to process staged items", e);
            } finally {
                // Clean up temporary file
                cleanupStagedFile(stagedFile);
            }
        }, virtualThreadExecutor);
    }

    /**
     * Loads all items from a staged file.
     * 
     * @param stagedFile the path to the staged file
     * @return list of loaded test items
     * @throws IOException if file reading fails
     */
    private List<TestItem> loadItemsFromFile(Path stagedFile) throws IOException {
        logger.debug("Loading items from staged file: {}", stagedFile);

        try (BufferedReader reader = Files.newBufferedReader(stagedFile)) {
            // Read the entire file content
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            // Parse JSON array
            TestItem[] itemArray = objectMapper.readValue(content.toString(), TestItem[].class);
            List<TestItem> items = new ArrayList<>();
            for (TestItem item : itemArray) {
                items.add(item);
            }

            logger.debug("Loaded {} items from staged file", items.size());
            return items;
        }
    }

    /**
     * Cleans up a staged temporary file.
     * 
     * @param stagedFile the path to the staged file
     */
    private void cleanupStagedFile(Path stagedFile) {
        if (stagedFile != null) {
            try {
                Files.deleteIfExists(stagedFile);
                logger.debug("Cleaned up staged file: {}", stagedFile);
            } catch (IOException e) {
                logger.warn("Failed to clean up staged file: {}", stagedFile, e);
            }
        }
    }

    /**
     * Checks if file staging should be used based on dataset size.
     * 
     * @param itemCount the number of items
     * @return true if file staging should be used
     */
    public boolean shouldUseFileStaging(int itemCount) {
        return itemCount >= FILE_STAGING_THRESHOLD;
    }

    /**
     * Gets the file staging threshold.
     * 
     * @return the threshold for using file staging
     */
    public int getFileStagingThreshold() {
        return FILE_STAGING_THRESHOLD;
    }

    /**
     * Gets the batch size for file operations.
     * 
     * @return the batch size
     */
    public int getBatchSize() {
        return BATCH_SIZE;
    }

    /**
     * Functional interface for processing batches of items.
     */
    @FunctionalInterface
    public interface BatchProcessor {
        void processBatch(List<TestItem> batch);
    }
}