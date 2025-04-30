package org.manishsharan.cloudtransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manishsharan.cloudtransfer.config.*;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.gcp.GcpDataDestination;
import org.manishsharan.cloudtransfer.gcp.GcpDataSource;
import org.manishsharan.cloudtransfer.local.LocalDataDestination;
import org.manishsharan.cloudtransfer.local.LocalDataSource;
import org.manishsharan.cloudtransfer.providers.GcpStorageProvider;
import org.manishsharan.cloudtransfer.providers.SftpClientProvider;
import org.manishsharan.cloudtransfer.service.TransferService;
import org.manishsharan.cloudtransfer.sftp.SftpDataDestination;
import org.manishsharan.cloudtransfer.sftp.SftpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.Objects; // *** ADDED THIS IMPORT ***
import java.util.Set;


/**
 * Core class for executing cloud transfer operations.
 * Designed to be used as a library.
 */
public class CloudTransfer {

    private static final Logger logger = LoggerFactory.getLogger(CloudTransfer.class);

    // Static utilities for JSON parsing and validation
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = validatorFactory.getValidator();

    private final TransferService transferService;

    /**
     * Default constructor. Initializes the internal TransferService.
     */
    public CloudTransfer() {
        this.transferService = new TransferService();
    }

    /**
     * Executes a transfer operation based on the provided configuration and client providers.
     * This is the main entry point for using the library.
     *
     * @param config The validated transfer configuration object.
     * @param sftpProvider The provider implementation for creating authenticated SFTP clients.
     * @param gcpProvider The provider implementation for creating authenticated GCS clients.
     * @throws IOException If configuration is invalid, initialization fails, or transfer fails.
     * @throws Exception Other potential exceptions during transfer or cleanup.
     */
    public void execute(TransferConfig config, SftpClientProvider sftpProvider, GcpStorageProvider gcpProvider) throws Exception {
        // Now Objects class can be resolved
        Objects.requireNonNull(config, "TransferConfig cannot be null");
        Objects.requireNonNull(sftpProvider, "SftpClientProvider cannot be null");
        Objects.requireNonNull(gcpProvider, "GcpStorageProvider cannot be null");

        logger.info("Executing transfer: Source type='{}', Destination type='{}'",
                    config.getSource().getType(), config.getDestination().getType());

        DataSource source = null;
        DataDestination destination = null;

        try {
            // --- Factory Logic to create Source and Destination (using providers) ---
            source = createDataSource(config.getSource(), sftpProvider, gcpProvider);
            destination = createDataDestination(config.getDestination(), sftpProvider, gcpProvider);
            // --- End Factory Logic ---

            logger.info("Initialized Source: {}", source.getDescription());
            logger.info("Initialized Destination: {}", destination.getDescription());

            // --- Execute Transfer (passing configs for single-file check) ---
            transferService.transfer(source, destination, config.getSource(), config.getDestination());
            // --- End Execute Transfer ---

            logger.info("Transfer process finished successfully via CloudTransfer.execute.");

        } finally {
            // --- Cleanup: Close Source and Destination ---
            // Note: ValidatorFactory is NOT closed here; its lifecycle is managed by the caller.
            closeResource("Source", source);
            closeResource("Destination", destination);
        }
    }

    /**
     * Loads and validates the TransferConfig from a JSON file path.
     * Static utility method.
     *
     * @param configFilePath Path to the configuration file.
     * @return Validated TransferConfig object, or null if loading/validation fails.
     */
    public static TransferConfig loadAndValidateConfigFromFile(String configFilePath) {
         logger.debug("Attempting to load and validate configuration from file: {}", configFilePath);
         try (InputStream is = Files.newInputStream(Paths.get(configFilePath))) {
            return loadAndValidateConfigFromStream(is);
        } catch (InvalidPathException e) { logger.error("Invalid config file path: '{}'", configFilePath, e); return null; }
        catch (NoSuchFileException e) { logger.error("Config file not found: '{}'", configFilePath, e); return null; }
        catch (IOException e) { logger.error("Failed to read config file '{}': {}", configFilePath, e.getMessage(), e); return null; }
        catch (Exception e) { logger.error("Unexpected error loading config file '{}': {}", configFilePath, e.getMessage(), e); return null; }
    }

     /**
     * Loads and validates the TransferConfig from an InputStream.
     * Static utility method.
     *
     * @param inputStream InputStream containing the JSON configuration.
     * @return Validated TransferConfig object, or null if parsing/validation fails.
     */
    public static TransferConfig loadAndValidateConfigFromStream(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "InputStream cannot be null"); // This call is now valid
         try {
            TransferConfig config = objectMapper.readValue(inputStream, TransferConfig.class);
            Set<ConstraintViolation<TransferConfig>> violations = validator.validate(config);
            if (!violations.isEmpty()) {
                logger.error("Configuration validation failed:");
                violations.forEach(v -> logger.error("  - Field '{}': {}", v.getPropertyPath(), v.getMessage()));
                return null;
            }
            logger.debug("Configuration parsed and validated successfully.");
            return config;
        } catch (IOException e) { logger.error("Failed to parse configuration from stream: {}", e.getMessage(), e); return null; }
        catch (Exception e) { logger.error("Unexpected error parsing/validating config from stream: {}", e.getMessage(), e); return null; }
    }

    /**
     * Closes the global ValidatorFactory. Should be called on application shutdown
     * by the application using this library.
     */
     public static void closeValidatorFactory() {
         if (validatorFactory != null) {
             try {
                 validatorFactory.close();
                 logger.info("CloudTransfer ValidatorFactory closed.");
             } catch (Exception e) {
                 logger.warn("Error closing ValidatorFactory: {}", e.getMessage());
             }
         }
     }


    // --- Private Static Factory Methods ---

    private static DataSource createDataSource(LocationConfig config,
                                               SftpClientProvider sftpProvider,
                                               GcpStorageProvider gcpProvider)
                                               throws IOException {
        logger.debug("Creating DataSource for type: {}", config.getType());
        switch (config.getType()) {
            case "LOCAL":
                return new LocalDataSource((LocalConfig) config);
            case "SFTP":
                 return new SftpDataSource((SftpConfig) config, sftpProvider);
            case "GCP_BUCKET":
                 return new GcpDataSource((GcpConfig) config, gcpProvider);
            default:
                throw new IllegalArgumentException("Unsupported source type: " + config.getType());
        }
    }

    private static DataDestination createDataDestination(LocationConfig config,
                                                         SftpClientProvider sftpProvider,
                                                         GcpStorageProvider gcpProvider)
                                                         throws IOException {
         logger.debug("Creating DataDestination for type: {}", config.getType());
         switch (config.getType()) {
            case "LOCAL":
                return new LocalDataDestination((LocalConfig) config);
            case "SFTP":
                return new SftpDataDestination((SftpConfig) config, sftpProvider);
            case "GCP_BUCKET":
                return new GcpDataDestination((GcpConfig) config, gcpProvider);
            default:
                throw new IllegalArgumentException("Unsupported destination type: " + config.getType());
        }
    }

     /** Private static helper method to close resources safely */
     private static void closeResource(String name, AutoCloseable resource) {
          if (resource != null) {
                try {
                    resource.close();
                    logger.debug("{} closed.", name);
                } catch (Exception e) {
                    // Log underlying exception for better debugging
                    logger.error("Error closing {}: {}", name, e.getMessage(), e);
                }
            }
     }
}
