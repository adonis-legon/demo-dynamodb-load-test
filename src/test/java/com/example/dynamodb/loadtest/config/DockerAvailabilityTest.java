package com.example.dynamodb.loadtest.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test to check Docker availability for TestContainers tests.
 */
class DockerAvailabilityTest {

    private static final Logger logger = LoggerFactory.getLogger(DockerAvailabilityTest.class);

    @Test
    void checkDockerAvailability() {
        boolean dockerAvailable = isDockerAvailable();
        logger.info("Docker availability: {}", dockerAvailable);

        if (dockerAvailable) {
            assertTrue(true, "Docker is available");
        } else {
            assumeTrue(false, "Docker is not available - skipping TestContainers tests");
        }
    }

    private boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                logger.info("Docker is available");
                return true;
            } else {
                logger.warn("Docker command failed or timed out");
                return false;
            }
        } catch (Exception e) {
            logger.warn("Error checking Docker availability: {}", e.getMessage());
            return false;
        }
    }

    @Test
    void checkDockerDaemonRunning() {
        assumeTrue(isDockerAvailable(), "Docker not available");

        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                logger.info("Docker daemon is running");
                assertTrue(true);
            } else {
                logger.warn("Docker daemon may not be running");
                assumeTrue(false, "Docker daemon not running");
            }
        } catch (Exception e) {
            logger.warn("Error checking Docker daemon: {}", e.getMessage());
            assumeTrue(false, "Cannot check Docker daemon");
        }
    }
}