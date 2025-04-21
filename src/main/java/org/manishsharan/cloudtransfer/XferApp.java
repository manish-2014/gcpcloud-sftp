package org.manishsharan.cloudtransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule; // If using Java 8+ time types in config
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.*;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.gcp.GcpDataDestination;
import org.manishsharan.cloudtransfer.gcp.GcpDataSource;
import org.manishsharan.cloudtransfer.local.LocalDataDestination;
import org.manishsharan.cloudtransfer.local.LocalDataSource;
import org.manishsharan.cloudtransfer.service.TransferService;
import org.manishsharan.cloudtransfer.sftp.SftpDataDestination;
import org.manishsharan.cloudtransfer.sftp.SftpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.Set;

public class XferApp {

    private static final Logger logger = LogManager.getLogger(XferApp.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()); // Optional: If using date/time types

    private static final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = validatorFactory.getValidator();


    public static void main(String[] args) {
        logger.info("Cloud Transfer Application Started.");

        if (args.length == 0) {
            logger.error("Configuration JSON file path must be provided as a command-line argument.");
            System.err.println("Usage: java -jar <your-jar-file>.jar /path/to/config.json");
            // Exit with a non-zero status code for errors
            System.exit(1);
        }
        String configFilePath = args[0];
        logger.info("Attempting to load configuration from: {}", configFilePath);

        TransferConfig config = loadAndValidateConfig(configFilePath);
        if (config == null) {
             System.exit(1); // Exit if config loading/validation failed
        }

        logger.info("Configuration loaded successfully: Source type='{}', Destination type='{}'",
                    config.getSource().getType(), config.getDestination().getType());

        DataSource source = null;
        DataDestination destination = null;
        boolean success = false;

        try {
            // --- Factory Logic to create Source and Destination ---
            source = createDataSource(config.getSource());
            destination = createDataDestination(config.getDestination());

            logger.info("Initialized Source: {}", source.getDescription());
            logger.info("Initialized Destination: {}", destination.getDescription());

            // --- Execute Transfer ---
            TransferService transferService = new TransferService();
            transferService.transfer(source, destination, config.getSource(), config.getDestination());

            logger.info("Transfer process finished successfully.");
            success = true;

        } catch (Exception e) { // Catch initialization or transfer errors
            logger.error("Transfer failed: {}", e.getMessage(), e);
            // Log the full stack trace for debugging
            // logger.error("Stack trace:", e);
        } finally {
            // --- Cleanup: Close Source and Destination ---
            // Use separate try-catch blocks to ensure both are attempted
            if (source != null) {
                try {
                    source.close();
                    logger.debug("Source closed.");
                } catch (Exception e) {
                    logger.error("Error closing source: {}", e.getMessage(), e);
                }
            }
            if (destination != null) {
                try {
                    destination.close();
                    logger.debug("Destination closed.");
                } catch (Exception e) {
                    logger.error("Error closing destination: {}", e.getMessage(), e);
                }
            }
            // Close validator resources
            if (validatorFactory != null) {
                try {
                    validatorFactory.close();
                    logger.debug("ValidatorFactory closed.");
                 } catch (Exception e) {
                    logger.warn("Error closing ValidatorFactory: {}", e.getMessage());
                 }
            }
            logger.info("Cloud Transfer Application Finished.");
            // Exit with appropriate status code
            System.exit(success ? 0 : 1);
        }
    }

    /**
     * Loads and validates the TransferConfig from a JSON file.
     */
    private static TransferConfig loadAndValidateConfig(String configFilePath) {
         try (InputStream is = Files.newInputStream(Paths.get(configFilePath))) {
            TransferConfig config = objectMapper.readValue(is, TransferConfig.class);

            // --- Bean Validation ---
            Set<ConstraintViolation<TransferConfig>> violations = validator.validate(config);
            if (!violations.isEmpty()) {
                logger.error("Configuration validation failed:");
                for (ConstraintViolation<TransferConfig> violation : violations) {
                    // Log violation property path and message
                    logger.error("  - Field '{}': {}", violation.getPropertyPath(), violation.getMessage());
                }
                 logger.error("Please check your configuration file: {}", configFilePath);
                return null;
            }

            return config;
        } catch (InvalidPathException e) {
             logger.error("Invalid configuration file path provided: '{}'", configFilePath, e);
             return null;
         } catch (NoSuchFileException e) {
            logger.error("Configuration file not found: '{}'", configFilePath, e);
            return null;
        } catch (IOException e) { // Catches Jackson parsing errors too
            logger.error("Failed to read or parse configuration file '{}': {}", configFilePath, e.getMessage(), e);
            return null;
        } catch (Exception e) { // Catch any other unexpected error during loading
             logger.error("Unexpected error loading configuration '{}': {}", configFilePath, e.getMessage(), e);
             return null;
        }
    }


    /**
     * Factory method to create a DataSource based on LocationConfig.
     */
    private static DataSource createDataSource(LocationConfig config) throws IOException {
        logger.debug("Creating DataSource for type: {}", config.getType());
        switch (config.getType()) {
            case "LOCAL":
                return new LocalDataSource((LocalConfig) config);
            case "SFTP":
                 // *** UPDATED: Uncommented and enabled SFTP ***
                 return new SftpDataSource((SftpConfig) config);
                 // throw new UnsupportedOperationException("SFTP DataSource not yet fully implemented."); // Removed
            case "GCP_BUCKET":
                 // *** UPDATED: Uncommented and enabled GCP ***
                 return new GcpDataSource((GcpConfig) config);
                 // throw new UnsupportedOperationException("GCP DataSource not yet fully implemented."); // Removed
            // Add cases for new types here
            default:
                throw new IllegalArgumentException("Unsupported source type: " + config.getType());
        }
    }

    /**
     * Factory method to create a DataDestination based on LocationConfig.
     */
    private static DataDestination createDataDestination(LocationConfig config) throws IOException {
         logger.debug("Creating DataDestination for type: {}", config.getType());
         switch (config.getType()) {
            case "LOCAL":
                return new LocalDataDestination((LocalConfig) config);
            case "SFTP":
                 // *** UPDATED: Uncommented and enabled SFTP ***
                return new SftpDataDestination((SftpConfig) config);
                 // throw new UnsupportedOperationException("SFTP DataDestination not yet fully implemented."); // Removed
            case "GCP_BUCKET":
                 // *** UPDATED: Uncommented and enabled GCP ***
                return new GcpDataDestination((GcpConfig) config);
                 // throw new UnsupportedOperationException("GCP DataDestination not yet fully implemented."); // Removed
            // Add cases for new types here
            default:
                throw new IllegalArgumentException("Unsupported destination type: " + config.getType());
        }
    }
}