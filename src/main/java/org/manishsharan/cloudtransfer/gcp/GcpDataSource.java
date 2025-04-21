package org.manishsharan.cloudtransfer.gcp;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.GcpConfig;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * DataSource implementation for reading from Google Cloud Storage.
 * Supports both prefix (folder) and single object modes. Implements recursive listing for prefixes.
 */
public class GcpDataSource implements DataSource {

    private static final Logger logger = LogManager.getLogger(GcpDataSource.class);

    private final GcpConfig config;
    private final Storage storage;
    private final String bucketName;
    private final String folderPrefix;       // Used in directory mode
    private final String effectiveObjectName; // Used in single file mode
    private final boolean isSingleFileMode;

    public GcpDataSource(GcpConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "GcpConfig cannot be null");
        this.isSingleFileMode = config.isSingleFile(); // Check mode from config
        this.bucketName = config.getBucket();
        this.folderPrefix = config.getNormalizedFolderPrefix();
        this.effectiveObjectName = config.getEffectiveObjectName(); // Will be null if not single file mode
        logger.info("Initializing GCP Data Source for: {}", config.getDescription());

        try {
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
                    new FileInputStream(config.getServiceAccountKeyPath()));

            StorageOptions storageOptions = StorageOptions.newBuilder()
                    .setCredentials(credentials)
                    .build();

            this.storage = storageOptions.getService();
            logger.info("GCP Storage client initialized successfully for source.");

            // Validate path based on mode
            validateSourcePath();

        } catch (IOException e) {
            logger.error("Failed to initialize GCP Storage client: {}", e.getMessage(), e);
            // Note: storage object might be null here if init failed early
            // The close() method should handle null checks.
            close();
            throw new IOException("Failed to initialize GCP Storage client", e);
        }
    }

    /** Validates the configured source path/object based on the mode */
    private void validateSourcePath() throws IOException {
        if (isSingleFileMode) {
            // Single file mode: Check if the specific object exists
            logger.debug("Validating GCS source object gs://{}/{}...", bucketName, effectiveObjectName);
            try {
                Blob blob = storage.get(bucketName, effectiveObjectName);
                if (blob == null || !blob.exists()) {
                    // exists() check might be redundant if get() returns null, but belt-and-suspenders
                    throw new IOException("GCS source object does not exist: gs://" + bucketName + "/" + effectiveObjectName);
                }
                 // We could check blob.isDirectory() here, but GCS files can have names ending in /
                 // Rely on openSpecificInputStream failing if it's truly just a directory marker.
                logger.debug("GCS source object gs://{}/{} verified.", bucketName, effectiveObjectName);
            } catch (StorageException e) {
                throw new IOException("Failed to verify GCS source object status: gs://" + bucketName + "/" + effectiveObjectName, e);
            }
        } else {
            // Directory mode: Check if bucket is accessible (prefix existence isn't strictly needed)
            logger.debug("Validating GCS source bucket '{}' accessibility for prefix '{}'...", bucketName, folderPrefix);
            try {
                // A simple get request on the bucket checks existence and basic permissions
                storage.get(bucketName);
                logger.debug("GCS source bucket '{}' is accessible.", bucketName);
            } catch (StorageException e) {
                 throw new IOException("Failed to access GCS source bucket: " + bucketName, e);
            }
        }
    }


    @Override
    public Stream<ItemInfo> listItems() throws IOException, UnsupportedOperationException {
        if (isSingleFileMode) {
            logger.error("listItems() called but GcpDataSource is configured for single file mode.");
            throw new UnsupportedOperationException("listItems is not supported in single file mode for GcpDataSource");
        }

        // Existing recursive prefix listing logic
        logger.debug("Recursively listing items in GCS bucket '{}' with prefix '{}'", bucketName, folderPrefix);
        try {
            Page<Blob> blobPage = storage.list(bucketName, Storage.BlobListOption.prefix(this.folderPrefix));
            Iterable<Blob> blobIterable = blobPage.iterateAll();

            return StreamSupport.stream(blobIterable.spliterator(), false)
                    .filter(blob -> !(blob.getName().equals(this.folderPrefix) && blob.isDirectory()))
                    .map(blob -> {
                        String blobName = blob.getName();
                        boolean isDirectory = blob.isDirectory() || blobName.endsWith("/");
                        Long size = isDirectory ? null : blob.getSize();
                        logger.debug("[GcpMap] Processing Blob: name='{}', isDir={}", blobName, isDirectory);
                        String simpleName = GcpUtils.simpleNameFromBlobName(blobName);
                        String parentRelativePath = GcpUtils.parentRelativePathFromBlobName(this.folderPrefix, blobName);
                        logger.debug("[GcpMap] Calculated: simpleName='{}', parentRelativePath='{}'", simpleName, parentRelativePath);
                        long effectiveSize = (size == null) ? (isDirectory ? -1L : 0L) : size;
                        if (simpleName.isEmpty()) {
                            logger.warn("[GcpMap] Skipping blob due to empty simple name: {}", blobName);
                            return null;
                        }
                        return new ItemInfo(parentRelativePath, simpleName, isDirectory, effectiveSize);
                    })
                    .filter(Objects::nonNull);
        } catch (StorageException e) {
            logger.error("Failed to list GCS bucket '{}' prefix '{}': {}", bucketName, folderPrefix, e.getMessage(), e);
            throw new IOException("Failed to list GCS bucket " + bucketName + " prefix " + folderPrefix, e);
        }
    }

    @Override
    public InputStream openInputStream(ItemInfo item) throws IOException, UnsupportedOperationException {
         if (isSingleFileMode) {
             logger.error("openInputStream(ItemInfo) called but GcpDataSource is configured for single file mode.");
             throw new UnsupportedOperationException("openInputStream(ItemInfo) is not supported in single file mode for GcpDataSource");
         }

        // Existing logic for directory mode
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open InputStream for a GCS directory object: " + item.getFullRelativePath());
        }
        String fullBlobName = this.folderPrefix + item.getFullRelativePath();
        logger.debug("Opening input stream for GCS object: gs://{}/{}", bucketName, fullBlobName);
        return openGcsInputStream(fullBlobName); // Use helper
    }


    @Override
    public InputStream openSpecificInputStream(String sourceIdentifier) throws IOException, UnsupportedOperationException {
        if (!isSingleFileMode) {
            logger.error("openSpecificInputStream() called but GcpDataSource is configured for directory mode.");
            throw new UnsupportedOperationException("openSpecificInputStream is only supported in single file mode for GcpDataSource");
        }
        // sourceIdentifier should be the effective object name
        if (!sourceIdentifier.equals(this.effectiveObjectName)) {
             logger.error("Requested specific object '{}' does not match configured object name '{}'", sourceIdentifier, this.effectiveObjectName);
             throw new IllegalArgumentException("Requested specific object does not match configuration");
        }

        logger.debug("Opening specific input stream for GCS object: gs://{}/{}", bucketName, this.effectiveObjectName);
        // Validation happened in constructor, proceed to open
        return openGcsInputStream(this.effectiveObjectName); // Use helper
    }

    /** Helper method to open input stream for a given object name */
    private InputStream openGcsInputStream(String objectName) throws IOException {
         try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            ReadChannel reader = storage.reader(blobId);
            return Channels.newInputStream(reader);
        } catch (StorageException e) {
            logger.error("Failed to open GCS object 'gs://{}/{}' for reading: {}",
                         bucketName, objectName, e.getMessage(), e);
            if (e.getCode() == 404) { // Not Found
                throw new IOException("GCS object not found: gs://" + bucketName + "/" + objectName, e);
            } else if (e.getCode() == 403) { // Forbidden
                 throw new IOException("Permission denied opening GCS object: gs://" + bucketName + "/" + objectName, e);
            }
            throw new IOException("Failed to open GCS object gs://" + bucketName + "/" + objectName, e);
        }
    }


    @Override
    public String getDescription() {
        return config.getDescription(); // Config class handles description
    }

    @Override
    public void close() throws IOException {
        logger.debug("Closing GCP Data Source for: {}", config.getDescription());
        try {
            if (storage != null) {
                 // GCS client library generally manages connections, explicit close might not be needed/available.
                 // If using a specific closeable resource within storage, close it here.
                 logger.info("GCP Data Source resources assumed closed/managed by library.");
            }
        } catch (Exception e) {
            logger.error("Error closing GCP Storage client resources (if any): {}", e.getMessage(), e);
            // Decide if this should be re-thrown based on resource type
        }
    }
}