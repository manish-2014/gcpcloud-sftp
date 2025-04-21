package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import org.manishsharan.cloudtransfer.gcp.GcpUtils; // For path normalization

/**
 * Configuration specific to Google Cloud Storage locations.
 * Supports either a prefix ('folder') for multi-object operations
 * or a specific object ('file_name' relative to 'folder') for single-object operations.
 */
public class GcpConfig extends LocationConfig {

    @JsonProperty(required = true)
    @NotBlank(message = "GCP 'bucket' name must be provided")
    private String bucket;

    @JsonProperty("folder") // Optional: Prefix/folder within the bucket. Treat as root if null/empty.
    private String folder;

    @JsonProperty("file_name") // Optional: Specific object/file name relative to the folder (or bucket root).
    private String fileName;    // If present, indicates single object operation.

    @JsonProperty("service_account_key_path")
    @NotBlank(message = "GCP 'service_account_key_path' must be provided")
    private String serviceAccountKeyPath;

    // TODO: Add other potential auth methods if needed (e.g., ADC - Application Default Credentials)

    // --- Getters and Setters ---
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getServiceAccountKeyPath() { return serviceAccountKeyPath; }
    public void setServiceAccountKeyPath(String serviceAccountKeyPath) { this.serviceAccountKeyPath = serviceAccountKeyPath; }

    /**
     * Checks if this configuration represents a single file/object transfer.
     * Determined by the presence of a non-blank file_name.
     * @return true if fileName is configured, false otherwise.
     */
    public boolean isSingleFile() {
        // fileName existing and being non-blank indicates a single file operation
        return fileName != null && !fileName.isBlank();
    }

    /**
     * Gets the normalized folder prefix.
     * Ensures it ends with "/" if not empty, returns "" for root.
     * @return The normalized prefix string.
     */
    public String getNormalizedFolderPrefix() {
        return GcpUtils.normalizeFolderPrefix(this.folder);
    }

    /**
     * Gets the full object name if configured for single file transfer.
     * Combines the normalized folder (prefix) and the file name.
     * Returns null if not in single file mode (i.e., fileName is not set).
     *
     * @return The full object name (e.g., "prefix/sub/file.txt" or "file.txt" for root) or null.
     */
    public String getEffectiveObjectName() {
        if (!isSingleFile()) {
            return null; // Not applicable for folder operations
        }
        String normalizedPrefix = getNormalizedFolderPrefix(); // e.g., "folder/" or ""
        String cleanFileName = fileName.trim();

        // Avoid double slashes if filename starts with one (shouldn't normally happen)
        while (normalizedPrefix.length() > 0 && cleanFileName.startsWith("/")) {
             cleanFileName = cleanFileName.substring(1);
        }
        // Simply concatenate normalized prefix and clean file name
        return normalizedPrefix + cleanFileName;
    }


    @Override
    public String getDescription() {
         String operationTarget;
         if (isSingleFile()) {
             // For single file, show the calculated effective object name
             operationTarget = "file=" + getEffectiveObjectName();
         } else {
              // For folder mode, show the normalized prefix (or root)
              String prefix = getNormalizedFolderPrefix();
              operationTarget = "folder=" + (prefix.isEmpty() ? "(bucket root)" : prefix);
         }
         // Combine bucket and target description
         return String.format("GCP[bucket=%s, %s, key=***]", bucket, operationTarget);
    }

     @Override
    public String toString() {
        // Include all relevant fields for debugging
        return "GcpConfig{" +
               "type='" + type + '\'' +
               ", bucket='" + bucket + '\'' +
               ", folder='" + folder + '\'' +
               ", fileName='" + fileName + '\'' + // Added fileName
               ", serviceAccountKeyPath='********'" +
               '}';
    }
}