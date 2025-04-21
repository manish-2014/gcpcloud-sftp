package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue; // For validation
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank; // For required fields like server/user/key

/**
 * Configuration specific to SFTP locations.
 * Supports either a directory ('path') or a single file ('file_path').
 */
public class SftpConfig extends LocationConfig {

    @JsonProperty("sftp_server")
    @NotBlank(message = "SFTP 'sftp_server' must be provided")
    private String sftpServer;

    @JsonProperty("sftp_port")
    @Min(value = 1, message = "SFTP port must be between 1 and 65535")
    @Max(value = 65535, message = "SFTP port must be between 1 and 65535")
    private int sftpPort = 22; // Default SFTP port

    @JsonProperty("ssh_key")
    @NotBlank(message = "SFTP 'ssh_key' path must be provided (password auth not implemented)")
    private String sshKeyPath;
    // Consider adding sshKeyPassphrase if keys can be protected

    @JsonProperty("ssh_user")
    @NotBlank(message = "SFTP 'ssh_user' must be provided")
    private String sshUser;

    // --- Path fields (mutually exclusive) ---
    @JsonProperty("path") // Remote base path for directory operations
    private String path;

    @JsonProperty("file_path") // Remote file path for single file operations
    private String filePath;


    // --- Getters and Setters ---
    public String getSftpServer() { return sftpServer; }
    public void setSftpServer(String sftpServer) { this.sftpServer = sftpServer; }
    public int getSftpPort() { return sftpPort; }
    public void setSftpPort(int sftpPort) { this.sftpPort = sftpPort; }
    public String getSshKeyPath() { return sshKeyPath; }
    public void setSshKeyPath(String sshKeyPath) { this.sshKeyPath = sshKeyPath; }
    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }


    /**
     * Validation rule: Ensures either 'path' OR 'file_path' is set, but not both.
     * Uses Jakarta Bean Validation. isBlank() requires Java 11+.
     * @return true if validation passes, false otherwise.
     */
    @AssertTrue(message = "Exactly one of 'path' (for directory) or 'file_path' (for single file) must be provided and non-blank for SFTP location")
    private boolean isPathOrFilePathValid() {
        boolean pathPresent = path != null && !path.isBlank();
        boolean filePathPresent = filePath != null && !filePath.isBlank();
        // Use XOR (^) to ensure exactly one is true (present and not blank)
        return pathPresent ^ filePathPresent;
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
        String targetPath;
        String type;
         if (isSingleFile()) {
            targetPath = filePath;
            type = "file";
         } else if (path != null && !path.isBlank()) {
            targetPath = path;
            type = "path";
         } else {
             targetPath = "[invalid config]";
             type = "path"; // Default assumption
         }
        return String.format("SFTP[user=%s, host=%s:%d, %s=%s, key=***]",
                             sshUser, sftpServer, sftpPort, type, targetPath);
    }

    @Override
    public String toString() {
         String pathInfo = isSingleFile() ? ", filePath='" + filePath + '\'' : ", path='" + path + '\'';
        return "SftpConfig{" +
               "type='" + type + '\'' +
               ", sftpServer='" + sftpServer + '\'' +
               ", sftpPort=" + sftpPort +
               ", sshKeyPath='********'" +
               ", sshUser='" + sshUser + '\'' +
               pathInfo + // Add path or file_path
               '}';
    }
}