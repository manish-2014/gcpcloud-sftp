package org.manishsharan.serverless.localxsftp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Holds configuration loaded from a JSON file for various SFTP operations.
 * Uses Jackson annotations to map JSON fields to Java fields.
 */
public class SftpConfig {

    // --- Define the possible SFTP operations ---
    public enum SftpOperation {
        UPLOAD_FILE,
        UPLOAD_FOLDER,
        DOWNLOAD_FILE,
        DOWNLOAD_FOLDER,
        LIST_REMOTE
    }

    @JsonProperty(value = "operation", required = true) // Make operation type mandatory
    private SftpOperation operation;

    @JsonProperty("sftp_server")
    private String sftpServer;

    @JsonProperty("sftp_port")
    private int sftpPort = 22; // Default SFTP port

    @JsonProperty("ssh_key")
    private String sshKey;

    @JsonProperty("ssh_user")
    private String sshUser;

    // --- Generic Paths ---
    // Represents the local file/folder path for uploads/downloads
    @JsonProperty("local_path")
    private String localPath;

    // Represents the remote file/folder path for uploads/downloads/listing
    @JsonProperty("remote_path")
    private String remotePath;


    // --- Getters and Setters ---

    public SftpOperation getOperation() {
        return operation;
    }

    public void setOperation(SftpOperation operation) {
        this.operation = operation;
    }

    public String getSftpServer() {
        return sftpServer;
    }

    public void setSftpServer(String sftpServer) {
        this.sftpServer = sftpServer;
    }

    public int getSftpPort() {
        return sftpPort;
    }

    public void setSftpPort(int sftpPort) {
        // Basic validation
        if (sftpPort <= 0 || sftpPort > 65535) {
            throw new IllegalArgumentException("Invalid SFTP port number: " + sftpPort);
        }
        this.sftpPort = sftpPort;
    }

    public String getSshKey() {
        return sshKey;
    }

    public void setSshKey(String sshKey) {
        this.sshKey = sshKey;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = remotePath;
    }


    /**
     * Validates if the necessary configuration parameters are present for the selected operation.
     * Throws IllegalArgumentException if validation fails.
     */
    public void validate() {
        if (sftpServer == null || sftpServer.trim().isEmpty()) {
            throw new IllegalArgumentException("sftp_server must be provided.");
        }
        if (sshUser == null || sshUser.trim().isEmpty()) {
            throw new IllegalArgumentException("ssh_user must be provided.");
        }
        if (sshKey == null || sshKey.trim().isEmpty()) {
             // Consider allowing password authentication as an alternative later
             // For now, key is required
            throw new IllegalArgumentException("ssh_key must be provided.");
        }
        if (operation == null) {
             // Should be caught by @JsonProperty(required=true), but good to double-check
            throw new IllegalArgumentException("operation must be provided.");
        }

        // Validate paths based on operation
        switch (operation) {
            case UPLOAD_FILE:
            case DOWNLOAD_FILE:
            case UPLOAD_FOLDER:
            case DOWNLOAD_FOLDER:
                if (localPath == null || localPath.trim().isEmpty()) {
                    throw new IllegalArgumentException("local_path must be provided for " + operation);
                }
                if (remotePath == null || remotePath.trim().isEmpty()) {
                    throw new IllegalArgumentException("remote_path must be provided for " + operation);
                }
                break;
            case LIST_REMOTE:
                if (remotePath == null || remotePath.trim().isEmpty()) {
                    throw new IllegalArgumentException("remote_path must be provided for " + operation);
                }
                // localPath is not needed for LIST_REMOTE
                break;
            default:
                 // Should not happen if enum is used correctly
                throw new IllegalStateException("Unknown operation selected: " + operation);
        }
    }


    @Override
    public String toString() {
        // Mask sensitive info like key path if needed in real logs
        return "SftpConfig{" +
                "operation=" + operation +
                ", sftpServer='" + sftpServer + '\'' +
                ", sftpPort=" + sftpPort +
                ", sshKey='********'" + // Mask key path
                ", sshUser='" + sshUser + '\'' +
                ", localPath='" + localPath + '\'' +
                ", remotePath='" + remotePath + '\'' +
                '}';
    }
}