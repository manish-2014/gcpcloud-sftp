package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank; // Keep for server/user

/**
 * Configuration specific to SFTP locations.
 * Supports either a directory ('path') or a single file ('file_path').
 * Credentials (like SSH keys) are handled externally.
 */
public class SftpConfig extends LocationConfig {

    @JsonProperty("sftp_server")
    @NotBlank(message = "SFTP 'sftp_server' must be provided")
    private String sftpServer;

    @JsonProperty("sftp_port")
    @Min(value = 1, message = "SFTP port must be between 1 and 65535")
    @Max(value = 65535, message = "SFTP port must be between 1 and 65535")
    private int sftpPort = 22; // Default SFTP port

    // REMOVED: sshKeyPath - will be handled by SftpClientProvider
    // @JsonProperty("ssh_key")
    // @NotBlank(message = "SFTP 'ssh_key' path must be provided (password auth not implemented)")
    // private String sshKeyPath;

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
    // REMOVED: getSshKeyPath() / setSshKeyPath()
    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }


    /**
     * Validation rule: Ensures either 'path' OR 'file_path' is set, but not both.
     */
    @AssertTrue(message = "Exactly one of 'path' (for directory) or 'file_path' (for single file) must be provided and non-blank for SFTP location")
    private boolean isPathOrFilePathValid() {
        boolean pathPresent = path != null && !path.isBlank();
        boolean filePathPresent = filePath != null && !filePath.isBlank();
        return pathPresent ^ filePathPresent;
    }

    /**
     * Checks if this configuration represents a single file transfer.
     */
    public boolean isSingleFile() {
        return filePath != null && !filePath.isBlank();
    }

     /**
     * Gets the effective path (either directory path or single file path).
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
             type = "path";
         }
        // REMOVED key info from description
        return String.format("SFTP[user=%s, host=%s:%d, %s=%s]",
                             sshUser, sftpServer, sftpPort, type, targetPath);
    }

    @Override
    public String toString() {
         String pathInfo = isSingleFile() ? ", filePath='" + filePath + '\'' : ", path='" + path + '\'';
        return "SftpConfig{" +
               "type='" + type + '\'' +
               ", sftpServer='" + sftpServer + '\'' +
               ", sftpPort=" + sftpPort +
               // REMOVED: sshKeyPath='********' +
               ", sshUser='" + sshUser + '\'' +
               pathInfo +
               '}';
    }
}
