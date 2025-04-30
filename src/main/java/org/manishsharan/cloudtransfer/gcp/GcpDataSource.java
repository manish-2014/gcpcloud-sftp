package org.manishsharan.cloudtransfer.gcp;

// Removed unused auth imports
// import com.google.auth.oauth2.GoogleCredentials;
// import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.api.gax.paging.Page;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manishsharan.cloudtransfer.config.GcpConfig;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.core.ItemInfo;
// Import the provider interface
import org.manishsharan.cloudtransfer.providers.GcpStorageProvider;

// Removed unused io/nio imports
// import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * DataSource implementation for reading from Google Cloud Storage.
 * Supports both prefix (folder) and single object modes. Uses GcpStorageProvider.
 */
public class GcpDataSource implements DataSource {

    private static final Logger logger = LoggerFactory.getLogger(GcpDataSource.class);

    private final GcpConfig config;
    private final GcpStorageProvider storageProvider; // Added provider field
    private final Storage storage; // Store client obtained from provider
    private final String bucketName;
    private final String folderPrefix;
    private final String effectiveObjectName;
    private final boolean isSingleFileMode;

    // Constructor now accepts GcpStorageProvider
    public GcpDataSource(GcpConfig config, GcpStorageProvider storageProvider) throws IOException {
        this.config = Objects.requireNonNull(config, "GcpConfig cannot be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "GcpStorageProvider cannot be null");
        this.isSingleFileMode = config.isSingleFile();
        this.bucketName = config.getBucket();
        this.folderPrefix = config.getNormalizedFolderPrefix();
        this.effectiveObjectName = config.getEffectiveObjectName();
        logger.info("Initializing GCP Data Source for: {}", config.getDescription());

        Storage tempStorage = null; // Temporary reference for cleanup
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

            // Validate path based on mode using the obtained client
            validateSourcePath();

        } catch (IOException e) {
            logger.error("Failed to initialize GCP source: {}", e.getMessage(), e);
            // No specific cleanup needed for GCS client usually, but good practice if provider held resources
            // close(); // Avoid calling close() here as 'storage' might not be assigned
            throw e;
        } catch (Exception e) {
             logger.error("Unexpected error during GCP source initialization via provider: {}", e.getMessage(), e);
             throw new IOException("Unexpected error initializing GCP source: " + e.getMessage(), e);
        }
    }

    /** Validates the configured source path/object based on the mode */
    private void validateSourcePath() throws IOException {
        // (Validation logic remains the same as before)
        if (isSingleFileMode) {
            logger.debug("Validating GCS source object gs://{}/{}...", bucketName, effectiveObjectName);
            try {
                Blob blob = storage.get(bucketName, effectiveObjectName);
                if (blob == null || !blob.exists()) {
                    throw new IOException("GCS source object does not exist: gs://" + bucketName + "/" + effectiveObjectName);
                }
                logger.debug("GCS source object gs://{}/{} verified.", bucketName, effectiveObjectName);
            } catch (StorageException e) {
                throw new IOException("Failed to verify GCS source object status: gs://" + bucketName + "/" + effectiveObjectName, e);
            }
        } else {
            logger.debug("Validating GCS source bucket '{}' accessibility for prefix '{}'...", bucketName, folderPrefix);
            try {
                storage.get(bucketName); // Check bucket access
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
        // (Recursive listing logic remains the same)
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
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open InputStream for a GCS directory object: " + item.getFullRelativePath());
        }
        String fullBlobName = this.folderPrefix + item.getFullRelativePath();
        logger.debug("Opening input stream for GCS object: gs://{}/{}", bucketName, fullBlobName);
        return openGcsInputStream(fullBlobName);
    }


    @Override
    public InputStream openSpecificInputStream(String sourceIdentifier) throws IOException, UnsupportedOperationException {
        if (!isSingleFileMode) {
            logger.error("openSpecificInputStream() called but GcpDataSource is configured for directory mode.");
            throw new UnsupportedOperationException("openSpecificInputStream is only supported in directory mode for GcpDataSource");
        }
        if (!sourceIdentifier.equals(this.effectiveObjectName)) {
             logger.error("Requested specific object '{}' does not match configured object name '{}'", sourceIdentifier, this.effectiveObjectName);
             throw new IllegalArgumentException("Requested specific object does not match configuration");
        }
        logger.debug("Opening specific input stream for GCS object: gs://{}/{}", bucketName, this.effectiveObjectName);
        return openGcsInputStream(this.effectiveObjectName);
    }

    /** Helper method to open input stream for a given object name */
    private InputStream openGcsInputStream(String objectName) throws IOException {
         // (Logic remains the same)
         try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            ReadChannel reader = storage.reader(blobId);
            return Channels.newInputStream(reader);
        } catch (StorageException e) {
            logger.error("Failed to open GCS object 'gs://{}/{}' for reading: {}", bucketName, objectName, e.getMessage(), e);
            if (e.getCode() == 404) throw new IOException("GCS object not found: gs://" + bucketName + "/" + objectName, e);
            else if (e.getCode() == 403) throw new IOException("Permission denied opening GCS object: gs://" + bucketName + "/" + objectName, e);
            throw new IOException("Failed to open GCS object gs://" + bucketName + "/" + objectName, e);
        }
    }


    @Override
    public String getDescription() {
        return config.getDescription();
    }

    @Override
    public void close() throws IOException {
        // (Logic remains the same)
        logger.debug("Closing GCP Data Source for: {}", config.getDescription());
        try {
            if (storage != null) {
                 logger.info("GCP Data Source resources assumed closed/managed by library.");
            }
        } catch (Exception e) {
            logger.error("Error closing GCP Storage client resources (if any): {}", e.getMessage(), e);
        }
    }
}
