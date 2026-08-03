package com.example.demo;

import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class DemoApp {

    private static final Logger log = Logger.getLogger(DemoApp.class.getName());

    public static void main(String[] args) {
        // Fallback to classpath logging.properties if java.util.logging.config.file system property is not provided
        if (System.getProperty("java.util.logging.config.file") == null) {
            try (InputStream is = DemoApp.class.getResourceAsStream("/logging.properties")) {
                if (is != null) {
                    LogManager.getLogManager().readConfiguration(is);
                }
            } catch (Exception e) {
                System.err.println("Could not load logging.properties: " + e.getMessage());
            }
        }

        log.info("Starting order processing batch");
        log.info("Fetching customer 404 from database");
        log.warning("Database returned empty record for customer 404");

        try {
            processCustomer(null);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to process customer 404", e);
        }
    }

    private static void processCustomer(String customerEmail) {
        if (customerEmail == null) {
            throw new IllegalArgumentException("Customer email cannot be null for notification dispatch");
        }
    }
}
