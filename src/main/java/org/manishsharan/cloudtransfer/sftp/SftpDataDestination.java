package org.manishsharan.cloudtransfer.sftp;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
// Removed unused transport/auth imports
// import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
// import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manishsharan.cloudtransfer.config.SftpConfig;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.ItemInfo;
// Import the provider interface
import org.manishsharan.cloudtransfer.providers.SftpClientProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.Objects;

/**
 * DataDestination implementation for writing to an SFTP server using SSHJ.
 * Destination must be configured with 'path' (directory). Uses SftpClientProvider.
 */
public class SftpDataDestination implements DataDestination {

    private static final Logger logger = LoggerFactory.getLogger(SftpDataDestination.class);

    private final SftpConfig config;
    private final SftpClientProvider clientProvider; // Added provider field
    private final SSHClient sshClient; // Store the client obtained from provider
    private final SFTPClient sftpClient;
    private final String baseRemotePath; // Destination base directory path

    // Constructor now accepts SftpClientProvider
    public SftpDataDestination(SftpConfig config, SftpClientProvider clientProvider) throws IOException {
        this.config = Objects.requireNonNull(config, "SftpConfig cannot be null");
        this.clientProvider = Objects.requireNonNull(clientProvider, "SftpClientProvider cannot be null");
        logger.info("Initializing SFTP Data Destination for: {}", config.getDescription());

        // --- Configuration Validation ---
        if (config.isSingleFile()) {
             logger.error("SFTP destination cannot be configured with 'file_path'. Use 'path' for the target directory.");
             throw new IllegalArgumentException("SFTP destination must be configured with 'path' (directory), not 'file_path'.");
        }
        String pathStr = config.getPath();
        if (pathStr == null || pathStr.isBlank()){
             throw new IllegalArgumentException("SFTP destination 'path' cannot be null or blank.");
        }
        this.baseRemotePath = SftpUtils.normalizeSftpPath(pathStr);
        // --- End Validation ---

        SSHClient tempSshClient = null; // For cleanup on failure
        try {
            // --- Get authenticated client from provider ---
            logger.debug("Requesting SSH client from SftpClientProvider...");
            tempSshClient = this.clientProvider.getSshClient(config); // Delegate client creation/auth
             if (tempSshClient == null || !tempSshClient.isConnected() || !tempSshClient.isAuthenticated()) {
                 throw new IOException("SftpClientProvider returned an invalid, disconnected, or unauthenticated SSHClient.");
            }
            this.sshClient = tempSshClient; // Assign to final field
            logger.info("SSH client obtained and validated from provider.");
            // --- End client acquisition ---

            this.sftpClient = sshClient.newSFTPClient();
             logger.info("SFTP session established for destination.");

            // Ensure base destination directory exists during initialization
            ensureRemoteDirectoryExistsInternal(this.baseRemotePath);

        } catch (IOException e) {
            logger.error("Failed to initialize SFTP destination: {}", e.getMessage(), e);
            if (tempSshClient != null) { /* cleanup */ } // Simplified cleanup call
            close(); // Call full close method for cleanup
            throw e;
        } catch (Exception e) {
             logger.error("Unexpected error during SFTP destination initialization via provider: {}", e.getMessage(), e);
             if (tempSshClient != null) { /* cleanup */ }
             close();
             throw new IOException("Unexpected error initializing SFTP destination: " + e.getMessage(), e);
        }
    }

    @Override
    public OutputStream openOutputStream(ItemInfo item) throws IOException {
        // Used for directory transfer mode
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open OutputStream for a directory item: " + item.getFullRelativePath());
        }
        String fullRemotePath = SftpUtils.buildRemotePath(this.baseRemotePath, item.getFullRelativePath());
        logger.debug("[OpenStream] Opening output stream for item: {}", fullRemotePath);
        ensureParentDirectoryExists(fullRemotePath); // Ensure parent dir exists
        return openRemoteFileOutputStream(fullRemotePath); // Use helper
    }

     @Override
    public OutputStream openSpecificOutputStream(String targetName) throws IOException, UnsupportedOperationException {
         // Used for single file transfer mode
         Objects.requireNonNull(targetName, "Target file name cannot be null");
         if (targetName.isBlank()) {
             throw new IllegalArgumentException("Invalid target file name for single file transfer (blank).");
         }
         if (targetName.contains("/") || targetName.contains("\\")) {
              logger.warn("Target file name '{}' contains path separators. Ensure this is intended relative to base path '{}'.", targetName, baseRemotePath);
         }

         String fullRemotePath = SftpUtils.buildRemotePath(this.baseRemotePath, targetName);
         logger.debug("[OpenSpecificStream] Opening specific output stream for remote file: {}", fullRemotePath);
         ensureParentDirectoryExists(fullRemotePath); // Ensure parent dir exists
         return openRemoteFileOutputStream(fullRemotePath); // Use helper
    }

    /** Helper to ensure parent directory of a given full path exists */
    private void ensureParentDirectoryExists(String fullPath) throws IOException {
        // (Logic remains the same)
         String parentPath = SftpUtils.getParentPath(fullPath);
         if (parentPath != null && !parentPath.isEmpty() && !parentPath.equals(this.baseRemotePath) && !parentPath.equals("/")) {
              logger.trace("Ensuring parent directory '{}' exists for target path '{}'", parentPath, fullPath);
              ensureRemoteDirectoryExistsInternal(parentPath);
         } else {
              logger.trace("Parent directory for '{}' is base path or root, skipping explicit creation check.", fullPath);
         }
    }

     /** Helper method to open a remote file output stream */
     private OutputStream openRemoteFileOutputStream(String absolutePath) throws IOException {
          // (Logic remains the same, uses corrected constructor)
          try {
            RemoteFile remoteFile = sftpClient.open(
                absolutePath,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            );
            return remoteFile.new RemoteFileOutputStream(0);
        } catch (SFTPException e) {
             logger.error("Failed to open SFTP file '{}' for writing: {} (Status: {})", absolutePath, e.getMessage(), e.getStatusCode(), e);
             if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                 logger.error(">>> Possible Cause: Parent directory for '{}' might not exist or failed creation.", absolutePath);
                 throw new IOException("Parent directory likely missing for remote file: " + absolutePath, e);
             } else if (e.getStatusCode() == Response.StatusCode.PERMISSION_DENIED) {
                  throw new IOException("Permission denied opening remote file for writing: " + absolutePath, e);
             } else {
                 throw new IOException("Failed to open remote file for writing: " + absolutePath, e);
             }
        }
     }

    @Override
    public void ensureDirectoryExists(ItemInfo item) throws IOException {
        // (Logic remains the same)
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        String dirToEnsure;
        if (item.isDirectory()) {
            dirToEnsure = SftpUtils.buildRemotePath(this.baseRemotePath, item.getFullRelativePath());
            logger.trace("[EnsureDir] Request to ensure directory for directory item: {}", dirToEnsure);
        } else {
            String parentRelativePath = item.getParentRelativePath();
            dirToEnsure = SftpUtils.buildRemotePath(this.baseRemotePath, parentRelativePath);
             logger.trace("[EnsureDir] Request to ensure parent directory for file item '{}': {}", item.getName(), dirToEnsure);
        }
        logger.info("[EnsureDir] For Item [parent='{}', name='{}', isDir={}], Calculated absolute dir to ensure: '{}'",
                    item.getParentRelativePath(), item.getName(), item.isDirectory(), dirToEnsure);
        if (dirToEnsure != null && !dirToEnsure.isEmpty() && !dirToEnsure.equals(this.baseRemotePath) && !dirToEnsure.equals("/")) {
             logger.debug("[EnsureDir] Calling internal method ensureRemoteDirectoryExistsInternal for: {}", dirToEnsure);
            ensureRemoteDirectoryExistsInternal(dirToEnsure);
        } else {
            logger.trace("[EnsureDir] Directory to ensure is null, empty, base path or root ('{}'), skipping explicit internal check/creation.", dirToEnsure);
        }
    }

    /** Internal helper to check/create a specific remote directory path (remains the same) */
    private void ensureRemoteDirectoryExistsInternal(String remoteDirPath) throws IOException {
        // (Keep the implementation with detailed logging and verification)
        remoteDirPath = SftpUtils.normalizeSftpPath(remoteDirPath);
        if (remoteDirPath.equals("/")) {
             logger.trace("[EnsureDirInternal] Attempted to ensure root directory ('/'), which always exists.");
            return;
        }
        try {
            logger.trace("[EnsureDirInternal] Checking status of '{}' with stat...", remoteDirPath);
            FileAttributes attrs = sftpClient.stat(remoteDirPath);
            if (attrs.getMode().getType() == FileMode.Type.DIRECTORY) {
                logger.trace("[EnsureDirInternal] Remote directory '{}' already exists (checked via stat).", remoteDirPath);
            } else {
                logger.error("[EnsureDirInternal] Remote path '{}' exists but is not a directory.", remoteDirPath);
                throw new IOException("Remote path exists but is not a directory: " + remoteDirPath);
            }
        } catch (SFTPException e) {
            if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                logger.debug("[EnsureDirInternal] Remote directory '{}' does not exist (checked via stat). Attempting mkdirs.", remoteDirPath);
                try {
                    logger.info("[EnsureDirInternal] Executing mkdirs for '{}'...", remoteDirPath);
                    sftpClient.mkdirs(remoteDirPath);
                    logger.info("[EnsureDirInternal] Completed mkdirs call for '{}'. Verifying existence...", remoteDirPath);
                    try {
                        FileAttributes checkAttrs = sftpClient.stat(remoteDirPath);
                         if (checkAttrs.getMode().getType() == FileMode.Type.DIRECTORY) {
                              logger.info("[EnsureDirInternal] Verification successful: '{}' exists and is a directory.", remoteDirPath);
                         } else {
                             logger.error("[EnsureDirInternal] Verification FAILED: '{}' exists but is NOT a directory after mkdirs call!", remoteDirPath);
                             throw new IOException("mkdirs call seemed to succeed but verification failed (path is not a directory): " + remoteDirPath);
                         }
                    } catch (IOException verifyEx) {
                         logger.error("[EnsureDirInternal] Verification FAILED: Could not stat '{}' after mkdirs call reported success.", remoteDirPath, verifyEx);
                         throw new IOException("mkdirs call seemed to succeed but verification failed (stat failed): " + remoteDirPath, verifyEx);
                    }
                } catch (IOException createOrVerifyEx) {
                    logger.error("[EnsureDirInternal] Failed to create remote directory '{}' with mkdirs or verification failed: {}", remoteDirPath, createOrVerifyEx.getMessage(), createOrVerifyEx);
                    throw new IOException("Failed to create remote directory or verify creation: " + remoteDirPath, createOrVerifyEx);
                }
            } else {
                logger.error("[EnsureDirInternal] Failed to check initial status of remote directory '{}': {} (Status: {})", remoteDirPath, e.getMessage(), e.getStatusCode(), e);
                throw new IOException("Failed to check initial status of remote directory: " + remoteDirPath, e);
            }
        }
    }


    @Override
    public String getDescription() {
        return config.getDescription();
    }

    @Override
    public void close() throws IOException {
        // Close logic remains the same
        logger.debug("Closing SFTP Data Destination for: {}", config.getDescription());
        IOException clientEx = null;
        try { if (sftpClient != null) sftpClient.close(); }
        catch (IOException e) { logger.error("Error closing SFTPClient: {}", e.getMessage(), e); clientEx = e; }
        finally {
             if (sshClient != null && sshClient.isConnected()) {
                try { sshClient.disconnect(); }
                catch (IOException e) {
                     logger.error("Error disconnecting SSHClient: {}", e.getMessage(), e);
                     if (clientEx == null) clientEx = e; else clientEx.addSuppressed(e);
                }
            }
        }
        logger.info("SFTP Data Destination resources closed for: {}", config.getDescription());
        if (clientEx != null) throw clientEx;
    }
}
