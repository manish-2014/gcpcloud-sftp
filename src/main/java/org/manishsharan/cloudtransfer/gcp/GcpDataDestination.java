package org.manishsharan.cloudtransfer.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.GcpConfig;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.Objects;

/**
 * DataDestination implementation for writing to Google Cloud Storage.
 * Supports both prefix (folder) and single object modes based on source configuration.
 * Destination config itself doesn't dictate mode, only bucket and optional base prefix (folder).
 */
public class GcpDataDestination implements DataDestination {

    private static final Logger logger = LogManager.getLogger(GcpDataDestination.class);

    private final GcpConfig config; // Destination config (bucket, optional folder)
    private final Storage storage;
    private final String bucketName;
    private final String folderPrefix; // Normalized prefix from destination config

    public GcpDataDestination(GcpConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "GcpConfig cannot be null");
        // Destination config *can* have fileName, but we ignore it here.
        // Destination logic depends on whether the *source* was single file.
        // The base path for writing is always bucket + optional folder prefix.
        this.bucketName = config.getBucket();
        this.folderPrefix = config.getNormalizedFolderPrefix(); // Use helper
        logger.info("Initializing GCP Data Destination for: {}", config.getDescription());

        try {
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
                    new FileInputStream(config.getServiceAccountKeyPath()));

            StorageOptions storageOptions = StorageOptions.newBuilder()
                    .setCredentials(credentials)
                    .build();

            this.storage = storageOptions.getService();
            logger.info("GCP Storage client initialized successfully for destination.");

            // Validate destination bucket write access? Optional.
            // Could do a test write or rely on first actual write attempt.
            // storage.get(bucketName); // At least check bucket exists

        } catch (IOException e) {
            logger.error("Failed to initialize GCP Storage client for destination: {}", e.getMessage(), e);
            close();
            throw new IOException("Failed to initialize GCP Storage client", e);
        }
    }

    @Override
    public OutputStream openOutputStream(ItemInfo item) throws IOException {
        // Used for directory transfer mode. Destination path derived from ItemInfo.
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open OutputStream for a directory item: " + item.getFullRelativePath());
        }

        // Combine destination prefix with item's full relative path
        String fullObjectName = this.folderPrefix + item.getFullRelativePath();
        logger.debug("[OpenStream] Opening output stream for GCS object: gs://{}/{}", bucketName, fullObjectName);

        return openGcsOutputStream(fullObjectName); // Use helper
    }

     @Override
    public OutputStream openSpecificOutputStream(String targetName) throws IOException, UnsupportedOperationException {
         // Used for single file transfer mode. Destination path is prefix + targetName.
         Objects.requireNonNull(targetName, "Target file name cannot be null");
         if (targetName.isBlank()) {
             throw new IllegalArgumentException("Invalid target file name for single file transfer (blank).");
         }
          // Basic check - maybe redundant if targetName is just filename from source
         if (targetName.contains("/") || targetName.contains("\\")) {
             logger.warn("Target file name '{}' contains path separators. Ensure this is intended relative to base prefix '{}'.", targetName, folderPrefix);
         }

         // Combine destination prefix with the target filename
         String fullObjectName = this.folderPrefix + targetName;
         logger.debug("[OpenSpecificStream] Opening specific output stream for GCS object: gs://{}/{}", bucketName, fullObjectName);

        return openGcsOutputStream(fullObjectName); // Use helper
    }

     /** Helper method to open GCS output stream for a given object name */
     private OutputStream openGcsOutputStream(String objectName) throws IOException {
          try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            // Let GCS determine content type, or set explicitly if needed:
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                                       // .setContentType("application/your-type")
                                        .build();

            WriteChannel writer = storage.writer(blobInfo, Storage.BlobWriteOption.disableGzipContent());
            return Channels.newOutputStream(writer);

        } catch (StorageException e) {
            logger.error("Failed to open GCS object 'gs://{}/{}' for writing: {}",
                         bucketName, objectName, e.getMessage(), e);
             if (e.getCode() == 403) { // Forbidden
                 throw new IOException("Permission denied opening GCS object for writing: gs://" + bucketName + "/" + objectName, e);
            }
            // Other errors (e.g., invalid bucket name, connection issues)
            throw new IOException("Failed to open GCS object for writing: gs://" + bucketName + "/" + objectName, e);
        }
     }


    @Override
    public void ensureDirectoryExists(ItemInfo item) throws IOException {
        // Only called during directory transfer mode (TransferService Pass 1).
        // GCS handles intermediate "directory" creation implicitly when objects are written.
        // Explicit 0-byte objects ending in '/' are usually not required.
        logger.trace("[EnsureDir] ensureDirectoryExists called for GCS item '{}'. No explicit action taken (implicit creation).", item.getFullRelativePath());
        // If explicit markers WERE needed, logic would go here (similar to commented out section previously).
    }


    @Override
    public String getDescription() {
        // Destination description doesn't depend on single file mode of source
        String prefix = config.getNormalizedFolderPrefix();
        String target = "folder=" + (prefix.isEmpty() ? "(bucket root)" : prefix);
        return String.format("GCP[bucket=%s, %s, key=***]", bucketName, target);
    }

    @Override
    public void close() throws IOException {
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