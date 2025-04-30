package org.manishsharan.cloudtransfer.providers;

import com.google.cloud.storage.Storage;
import org.manishsharan.cloudtransfer.config.GcpConfig;

import java.io.IOException;

/**
 * Interface for providing authenticated Google Cloud Storage client instances.
 * Implementations handle the specific mechanism for authentication (e.g., Application
 * Default Credentials (ADC), specific service account key streams, etc.).
 */
@FunctionalInterface
public interface GcpStorageProvider {

    /**
     * Creates and returns an authenticated Storage client instance.
     *
     * The implementation is responsible for:
     * 1. Determining the authentication method (e.g., ADC, key stream).
     * 2. Configuring StorageOptions with appropriate credentials and project ID (if needed).
     * 3. Returning the Storage service object.
     *
     * @param config The GCP configuration containing bucket name, etc.
     * (Note: It no longer contains the serviceAccountKeyPath).
     * The config might be used to determine project ID or other options if needed.
     * @return An authenticated Storage client instance.
     * @throws IOException If authentication or client creation fails.
     */
    Storage getStorageClient(GcpConfig config) throws IOException;

     // Similar to SFTP, Storage client resources are typically managed by the
     // client library or the DataSource/Destination using it.
}

