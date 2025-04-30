package org.manishsharan.cloudtransfer.gcp;

// Removed unused auth imports
// import com.google.auth.oauth2.GoogleCredentials;
// import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manishsharan.cloudtransfer.config.GcpConfig;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.ItemInfo;
// Import the provider interface
import org.manishsharan.cloudtransfer.providers.GcpStorageProvider;

// Removed unused io/nio imports
// import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.Objects;

/**
 * DataDestination implementation for writing to Google Cloud Storage.
 * Supports both prefix (folder) and single object modes based on source configuration.
 * Uses GcpStorageProvider for client creation.
 */
public class GcpDataDestination implements DataDestination {

    private static final Logger logger = LoggerFactory.getLogger(GcpDataDestination.class);

    // *** Enhancement: Define static final chunk size ***
    // GCS requires chunk size to be a multiple of 256 KiB (262,144 bytes)
    // Examples: 8 MiB = 8 * 1024 * 1024 = 8,388,608 bytes
    //          16 MiB = 16 * 1024 * 1024 = 16,777,216 bytes
    // Choose a size appropriate for your environment (larger chunks can be faster but use more memory)
    private static final int RESUMABLE_UPLOAD_CHUNK_SIZE_BYTES = 16 * 1024 * 1024; // 16 MiB example

    private final GcpConfig config; // Destination config (bucket, optional folder)
    private final GcpStorageProvider storageProvider; // Added provider field
    private final Storage storage; // Store client obtained from provider
    private final String bucketName;
    private final String folderPrefix; // Normalized prefix from destination config

    // Constructor now accepts GcpStorageProvider
    public GcpDataDestination(GcpConfig config, GcpStorageProvider storageProvider) throws IOException {
        this.config = Objects.requireNonNull(config, "GcpConfig cannot be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "GcpStorageProvider cannot be null");
        this.bucketName = config.getBucket();
        this.folderPrefix = config.getNormalizedFolderPrefix();
        logger.info("Initializing GCP Data Destination for: {}", config.getDescription());

        Storage tempStorage = null; // Temporary reference
        try {
            // --- Get authenticated client from provider ---
            logger.debug("Requesting Storage client from GcpStorageProvider...");
            tempStorage = this.storageProvider.getStorageClient(config); // Delegate client creation/auth
            if (tempStorage == null) {
                throw new IOException("GcpStorageProvider returned a null Storage client.");
            }
            this.storage = tempStorage; // Assign to final field
            logger.info("GCP Storage client obtained from provider.");
            // --- End client acquisition ---

            // Validate destination bucket write access? Optional.
            validateDestinationBucket();

        } catch (IOException e) {
            logger.error("Failed to initialize GCP destination: {}", e.getMessage(), e);
            close(); // Call close for potential cleanup if needed by provider/client
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error during GCP destination initialization via provider: {}", e.getMessage(), e);
            close();
            throw new IOException("Unexpected error initializing GCP destination: " + e.getMessage(), e);
        }
    }

    /** Validates basic accessibility of the destination bucket */
    private void validateDestinationBucket() throws IOException {
        logger.debug("Validating GCS destination bucket '{}' accessibility...", bucketName);
        try {
            storage.get(bucketName); // Check bucket exists and basic access
            // Further check write permissions? Could do a small test write/delete, but adds complexity/cost.
            logger.debug("GCS destination bucket '{}' is accessible.", bucketName);
        } catch (StorageException e) {
            throw new IOException("Failed to access GCS destination bucket: " + bucketName, e);
        }
    }

    @Override
    public OutputStream openOutputStream(ItemInfo item) throws IOException {
        // Used for directory transfer mode
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open OutputStream for a directory item: " + item.getFullRelativePath());
        }
        String fullObjectName = this.folderPrefix + item.getFullRelativePath();
        logger.debug("[OpenStream] Opening output stream for GCS object: gs://{}/{}", bucketName, fullObjectName);
        return openGcsOutputStream(fullObjectName);
    }

    @Override
    public OutputStream openSpecificOutputStream(String targetName) throws IOException, UnsupportedOperationException {
        // Used for single file transfer mode
        Objects.requireNonNull(targetName, "Target file name cannot be null");
        if (targetName.isBlank()) {
            throw new IllegalArgumentException("Invalid target file name for single file transfer (blank).");
        }
        if (targetName.contains("/") || targetName.contains("\\")) {
            logger.warn("Target file name '{}' contains path separators. Ensure this is intended relative to base prefix '{}'.", targetName, folderPrefix);
        }

        String fullObjectName = this.folderPrefix + targetName;
        logger.debug("[OpenSpecificStream] Opening specific output stream for GCS object: gs://{}/{}", bucketName, fullObjectName);
        return openGcsOutputStream(fullObjectName);
    }

    /** Helper method to open GCS output stream for a given object name */
    private OutputStream openGcsOutputStream(String objectName) throws IOException {
        try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

            // Get the writer using resumable uploads
            WriteChannel writer = storage.writer(blobInfo, Storage.BlobWriteOption.disableGzipContent());

            // *** Enhancement: Set chunk size using the static final variable ***
            writer.setChunkSize(RESUMABLE_UPLOAD_CHUNK_SIZE_BYTES);
            logger.debug("Setting GCS resumable upload chunk size to: {} bytes", RESUMABLE_UPLOAD_CHUNK_SIZE_BYTES);
            // *** End Enhancement ***

            return Channels.newOutputStream(writer);
        } catch (StorageException e) {
            logger.error("Failed to open GCS object 'gs://{}/{}' for writing: {}", bucketName, objectName, e.getMessage(), e);
            if (e.getCode() == 403) throw new IOException("Permission denied opening GCS object for writing: gs://" + bucketName + "/" + objectName, e);
            throw new IOException("Failed to open GCS object for writing: gs://" + bucketName + "/" + objectName, e);
        }
    }

    @Override
    public void ensureDirectoryExists(ItemInfo item) throws IOException {
        // (Logic remains the same - no-op for GCS)
        logger.trace("[EnsureDir] ensureDirectoryExists called for GCS item '{}'. No explicit action taken (implicit creation).", item.getFullRelativePath());
    }


    @Override
    public String getDescription() {
        // (Logic remains the same)
        String prefix = config.getNormalizedFolderPrefix();
        String target = "folder=" + (prefix.isEmpty() ? "(bucket root)" : prefix);
        return String.format("GCP[bucket=%s, %s]", bucketName, target); // Removed key info
    }

    @Override
    public void close() throws IOException {
        // (Logic remains the same)
        logger.debug("Closing GCP Data Destination for: {}", config.getDescription());
        try {
            if (storage != null) {
                logger.info("GCP Data Destination resources assumed closed/managed by library.");
            }
        } catch (Exception e) {
            logger.error("Error closing GCP Storage client resources (if any): {}", e.getMessage(), e);
        }
    }
}