package org.manishsharan.cloudtransfer.providers;


import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import org.manishsharan.cloudtransfer.config.GcpConfig;

import java.io.IOException;

/**
 * Default implementation of GcpStorageProvider that uses Application Default Credentials (ADC).
 * This automatically finds credentials in standard locations (environment variables,
 * gcloud config, Compute Engine metadata server, etc.).
 */
public class DefaultGcpStorageProvider implements GcpStorageProvider {

    private static final Logger logger = LoggerFactory.getLogger(DefaultGcpStorageProvider.class);

    // Consider making this a singleton if appropriate (no internal state)
    private static DefaultGcpStorageProvider instance;

    // Private constructor for potential singleton pattern
    private DefaultGcpStorageProvider() {}

    public static synchronized DefaultGcpStorageProvider getInstance() {
        if (instance == null) {
            instance = new DefaultGcpStorageProvider();
        }
        return instance;
    }


    @Override
    public Storage getStorageClient(GcpConfig config) throws IOException {
        logger.info("Attempting to get GCP Storage client using Application Default Credentials (ADC)...");
        try {
            // StorageOptions.getDefaultInstance() automatically uses ADC
            // You could potentially set project ID here if needed and not inferred:
            // StorageOptions options = StorageOptions.newBuilder()
            //         .setProjectId(config.getProjectId()) // If you add projectId to GcpConfig
            //         .build();
            // return options.getService();

            Storage storage = StorageOptions.getDefaultInstance().getService();
            if (storage == null) {
                 throw new IOException("Failed to get default GCP Storage instance (ADC might be missing or misconfigured).");
            }
            logger.info("Successfully obtained GCP Storage client via ADC.");
            return storage;
        } catch (Exception e) {
            // Catch broader exceptions during ADC lookup/initialization
             logger.error("Failed to initialize GCP Storage client using ADC: {}", e.getMessage(), e);
             throw new IOException("Failed to initialize GCP Storage client using ADC: " + e.getMessage(), e);
        }
    }
}
