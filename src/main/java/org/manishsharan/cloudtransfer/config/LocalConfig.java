package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue; // Import for class-level constraint

/**
 * Configuration specific to LOCAL filesystem locations.
 * Supports either a directory ('path') or a single file ('file_path').
 */
public class LocalConfig extends LocationConfig {

    @JsonProperty("path") // Path for directory operations
    private String path;

    @JsonProperty("file_path") // Path for single file operations
    private String filePath;

    // --- Getters and Setters ---
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Validation rule: Ensures either 'path' OR 'file_path' is set, but not both.
     * Uses Jakarta Bean Validation. isBlank() requires Java 11+.
     * @return true if validation passes, false otherwise.
     */
    @AssertTrue(message = "Exactly one of 'path' (for directory) or 'file_path' (for single file) must be provided and non-blank for LOCAL location")
    private boolean isPathOrFilePathValid() {
        boolean pathPresent = path != null && !path.isBlank();
        boolean filePathPresent = filePath != null && !filePath.isBlank();
        // Use XOR (^) to ensure exactly one is true (present and not blank)
        return pathPresent ^ filePathPresent;
        // Equivalent: return (pathPresent && !filePathPresent) || (!pathPresent && filePathPresent);
    }

    /**
     * Checks if this configuration represents a single file transfer.
     * @return true if filePath is configured, false otherwise.
     */
    public boolean isSingleFile() {
        // Assumes validation passed, so if filePath is present, path is not.
        return filePath != null && !filePath.isBlank();
    }

     /**
     * Gets the effective path (either directory path or single file path).
     * Assumes validation has passed.
     * @return The configured path string.
     */
     public String getEffectivePath() {
         return isSingleFile() ? filePath : path;
     }


    @Override
    public String getDescription() {
        if (isSingleFile()) {
             return "Local[file=" + filePath + "]";
        } else if (path != null && !path.isBlank()) { // Check needed in case validation hasn't run? Better safe.
             return "Local[path=" + path + "]";
        } else {
            return "Local[invalid - no path or file_path specified]"; // Should be caught by validation
        }
    }

     @Override
    public String toString() {
        // Customize toString to be more informative based on which path is set
        String details;
         if (isSingleFile()) {
             details = "filePath='" + filePath + '\'';
         } else {
             details = "path='" + path + '\'';
         }
        return "LocalConfig{" +
               "type='" + type + '\'' +
               ", " + details +
               '}';
    }
}