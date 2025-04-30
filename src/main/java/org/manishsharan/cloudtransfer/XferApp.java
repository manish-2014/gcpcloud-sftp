package org.manishsharan.cloudtransfer;

// Imports for config, providers, and logging are still needed
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manishsharan.cloudtransfer.config.*;
import org.manishsharan.cloudtransfer.providers.DefaultGcpStorageProvider;
import org.manishsharan.cloudtransfer.providers.DefaultSftpClientProvider;
import org.manishsharan.cloudtransfer.providers.GcpStorageProvider;
import org.manishsharan.cloudtransfer.providers.SftpClientProvider;

/**
 * Command-line interface (CLI) wrapper for the CloudTransfer library.
 * Parses command line arguments, loads configuration, sets up default providers,
 * and executes the transfer using the CloudTransfer class.
 */
public class XferApp {

    private static final Logger logger = LoggerFactory.getLogger(XferApp.class);

    // Keep ValidatorFactory initialization here if CloudTransfer.closeValidatorFactory() is static
    // Alternatively, CloudTransfer could manage its own static factory internally.
    // For now, keep static access for closing.

    public static void main(String[] args) {
        logger.info("Cloud Transfer CLI Started.");
        int exitCode = 1; // Default to failure exit code

        if (args.length == 0) {
            logger.error("Configuration JSON file path must be provided as a command-line argument.");
            System.err.println("Usage: java -jar <your-jar-file>.jar /path/to/config.json");
            System.exit(exitCode);
        }
        String configFilePath = args[0];
        logger.info("Attempting to load configuration from: {}", configFilePath);

        try {
            // Load and validate config using the library's utility method
            // Ensure CloudTransfer class is accessible (same package or imported)
            TransferConfig config = CloudTransfer.loadAndValidateConfigFromFile(configFilePath);
            if (config == null) {
                // Error already logged by load method
                System.exit(exitCode);
            }

            logger.info("Configuration loaded successfully: Source type='{}', Destination type='{}'",
                    config.getSource().getType(), config.getDestination().getType());

            // --- Instantiate Default Providers for CLI ---
            // For GCP, ADC is a sensible default.
            GcpStorageProvider gcpProvider = DefaultGcpStorageProvider.getInstance();
            // For SFTP, the default throws an error. A real CLI might need ways to configure this.
            SftpClientProvider sftpProvider = DefaultSftpClientProvider.getInstance();
            logger.warn("Using default providers. SFTP operations will fail unless a custom SftpClientProvider is implemented and used here.");


            // --- Create and Execute Transfer via Library ---
            CloudTransfer transferExecutor = new CloudTransfer();
            // Call the library's execute method
            transferExecutor.execute(config, sftpProvider, gcpProvider);
            // --- End Execution ---

            logger.info("Cloud Transfer CLI finished successfully.");
            exitCode = 0; // Set success exit code

        } catch (UnsupportedOperationException e) {
             // Catch specific error from default SFTP provider
             logger.error("Transfer failed: {}", e.getMessage());
             logger.error("This often means the default SFTP provider was used. A custom SftpClientProvider implementation is required for SFTP transfers via the CLI.");
        } catch (Exception e) {
            // Catch other exceptions from execute() or provider instantiation
            logger.error("Transfer failed unexpectedly: {}", e.getMessage(), e);
        } finally {
             // Close the static ValidatorFactory managed by the library class
             // Ensure CloudTransfer class is accessible (same package or imported)
             CloudTransfer.closeValidatorFactory();
             logger.info("Cloud Transfer CLI Finished.");
             System.exit(exitCode);
        }
    }

    // NOTE: loadAndValidateConfig, createDataSource, createDataDestination, closeResource
    // methods have been moved to the CloudTransfer class.
}
