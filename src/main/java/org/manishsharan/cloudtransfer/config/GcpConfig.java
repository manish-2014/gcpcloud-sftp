package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import org.manishsharan.cloudtransfer.gcp.GcpUtils; // For path normalization

/**
 * Configuration specific to Google Cloud Storage locations.
 * Supports both a prefix ('folder') for multi-object operations
 * or a specific object ('file_name' relative to 'folder') for single-object operations.
 * Credentials (like service account keys) are handled externally.
 */
public class GcpConfig extends LocationConfig {

    @JsonProperty(required = true)
    @NotBlank(message = "GCP 'bucket' name must be provided")
    private String bucket;

    @JsonProperty("folder") // Optional: Prefix/folder within the bucket. Treat as root if null/empty.
    private String folder;

    @JsonProperty("file_name") // Optional: Specific object/file name relative to the folder (or bucket root).
    private String fileName;    // If present, indicates single object operation.

    // REMOVED: serviceAccountKeyPath - will be handled by GcpStorageProvider
    // @JsonProperty("service_account_key_path")
    // @NotBlank(message = "GCP 'service_account_key_path' must be provided")
    // private String serviceAccountKeyPath;


    // --- Getters and Setters ---
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    // REMOVED: getServiceAccountKeyPath() / setServiceAccountKeyPath()


    /**
     * Checks if this configuration represents a single file/object transfer.
     */
    public boolean isSingleFile() {
        return fileName != null && !fileName.isBlank();
    }

    /**
     * Gets the normalized folder prefix.
     */
    public String getNormalizedFolderPrefix() {
        return GcpUtils.normalizeFolderPrefix(this.folder);
    }

    /**
     * Gets the full object name if configured for single file transfer.
     */
    public String getEffectiveObjectName() {
        if (!isSingleFile()) {
            return null;
        }
        String normalizedPrefix = getNormalizedFolderPrefix();
        String cleanFileName = fileName.trim();
        while (normalizedPrefix.length() > 0 && cleanFileName.startsWith("/")) {
             cleanFileName = cleanFileName.substring(1);
        }
        return normalizedPrefix + cleanFileName;
    }


    @Override
    public String getDescription() {
         String operationTarget;
         if (isSingleFile()) {
             operationTarget = "file=" + getEffectiveObjectName();
         } else {
              String prefix = getNormalizedFolderPrefix();
              operationTarget = "folder=" + (prefix.isEmpty() ? "(bucket root)" : prefix);
         }
         // REMOVED key info from description
         return String.format("GCP[bucket=%s, %s]", bucket, operationTarget);
    }

     @Override
    public String toString() {
        return "GcpConfig{" +
               "type='" + type + '\'' +
               ", bucket='" + bucket + '\'' +
               ", folder='" + folder + '\'' +
               ", fileName='" + fileName + '\'' +
               // REMOVED: serviceAccountKeyPath='********' +
               '}';
    }
}
