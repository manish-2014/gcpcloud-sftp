package org.manishsharan.cloudtransfer.sftp;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.SftpConfig;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.Objects;

/**
 * DataDestination implementation for writing to an SFTP server using SSHJ.
 * Destination must be configured with 'path' (directory).
 */
public class SftpDataDestination implements DataDestination {

    private static final Logger logger = LogManager.getLogger(SftpDataDestination.class);

    private final SftpConfig config;
    private final SSHClient sshClient;
    private final SFTPClient sftpClient;
    private final String baseRemotePath; // Destination is always treated as a base directory path

    public SftpDataDestination(SftpConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "SftpConfig cannot be null");
        this.sshClient = new SSHClient();
        logger.info("Initializing SFTP Data Destination for: {}", config.getDescription());

        // --- Configuration Validation ---
        // Destination configuration MUST specify a directory path, even if receiving a single file.
        if (config.isSingleFile()) {
             logger.error("SFTP destination cannot be configured with 'file_path'. Use 'path' for the target directory.");
             throw new IllegalArgumentException("SFTP destination must be configured with 'path' (directory), not 'file_path'.");
        }
        String pathStr = config.getPath(); // Use getPath for destination directory
        if (pathStr == null || pathStr.isBlank()){
             throw new IllegalArgumentException("SFTP destination 'path' cannot be null or blank.");
        }
        this.baseRemotePath = SftpUtils.normalizeSftpPath(pathStr);
        // --- End Validation ---


        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        logger.warn("Using PromiscuousHostKeyVerifier for SFTP destination. THIS IS INSECURE FOR PRODUCTION!");

        try {
            logger.debug("Connecting to SFTP server: {}:{}", config.getSftpServer(), config.getSftpPort());
            sshClient.connect(config.getSftpServer(), config.getSftpPort());

            logger.debug("Authenticating user '{}' with key file: {}", config.getSshUser(), config.getSshKeyPath());
            KeyProvider keyProvider = sshClient.loadKeys(config.getSshKeyPath());
            sshClient.authPublickey(config.getSshUser(), keyProvider);
            logger.info("SSH connection and authentication successful for destination.");

            this.sftpClient = sshClient.newSFTPClient();
             logger.info("SFTP session established for destination.");

            // Ensure base destination directory exists during initialization
            ensureRemoteDirectoryExistsInternal(this.baseRemotePath);

        } catch (IOException e) {
            logger.error("Failed to initialize SFTP destination connection: {}", e.getMessage(), e);
            close(); // Attempt cleanup
            throw e;
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

        // Ensure parent dir exists first (essential!)
        ensureParentDirectoryExists(fullRemotePath);

        return openRemoteFileOutputStream(fullRemotePath); // Use helper
    }

    @Override
    public OutputStream openSpecificOutputStream(String targetName) throws IOException, UnsupportedOperationException {
        // Used for single file transfer mode
        Objects.requireNonNull(targetName, "Target file name cannot be null");
        if (targetName.isBlank()) {
             throw new IllegalArgumentException("Invalid target file name for single file transfer (blank).");
        }
        // Basic check to prevent writing outside base path (though buildRemotePath might handle some cases)
         if (targetName.contains("/") || targetName.contains("\\")) {
              logger.warn("Target file name '{}' contains path separators. Ensure this is intended relative to base path '{}'.", targetName, baseRemotePath);
              // Consider throwing an error if only simple names are expected, depends on desired behaviour
         }


        String fullRemotePath = SftpUtils.buildRemotePath(this.baseRemotePath, targetName);
        logger.debug("[OpenSpecificStream] Opening specific output stream for remote file: {}", fullRemotePath);

        // Ensure parent directory exists first (essential!)
        ensureParentDirectoryExists(fullRemotePath);

        return openRemoteFileOutputStream(fullRemotePath); // Use helper
    }

    /** Helper to ensure parent directory of a given full path exists */
    private void ensureParentDirectoryExists(String fullPath) throws IOException {
         String parentPath = SftpUtils.getParentPath(fullPath); // Gets the absolute parent path
         if (parentPath != null && !parentPath.isEmpty() && !parentPath.equals(this.baseRemotePath) && !parentPath.equals("/")) {
              logger.trace("Ensuring parent directory '{}' exists for target path '{}'", parentPath, fullPath);
              ensureRemoteDirectoryExistsInternal(parentPath);
         } else {
              logger.trace("Parent directory for '{}' is base path or root, skipping explicit creation check.", fullPath);
         }
    }

     /** Helper method to open a remote file output stream */
     private OutputStream openRemoteFileOutputStream(String absolutePath) throws IOException {
          try {
            RemoteFile remoteFile = sftpClient.open(
                absolutePath,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            );
            return remoteFile.new RemoteFileOutputStream(0); // Use corrected constructor
        } catch (SFTPException e) {
             logger.error("Failed to open SFTP file '{}' for writing: {} (Status: {})",
                          absolutePath, e.getMessage(), e.getStatusCode(), e);
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
        // Used only for directory transfer mode by TransferService Pass 1
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        String dirToEnsure;

        if (item.isDirectory()) {
            dirToEnsure = SftpUtils.buildRemotePath(this.baseRemotePath, item.getFullRelativePath());
            logger.trace("[EnsureDir] Request to ensure directory for directory item: {}", dirToEnsure);
        } else {
            // When called for a FILE item in Pass 1, ensure its PARENT exists
            String parentRelativePath = item.getParentRelativePath();
            dirToEnsure = SftpUtils.buildRemotePath(this.baseRemotePath, parentRelativePath);
             logger.trace("[EnsureDir] Request to ensure parent directory for file item '{}': {}", item.getName(), dirToEnsure);
        }

        logger.info("[EnsureDir] Item: name='{}', isDir={}, parentRelativePath='{}' -> Calculated absolute dir to ensure: '{}'",
                    item.getName(), item.isDirectory(), item.getParentRelativePath(), dirToEnsure);

        if (dirToEnsure != null && !dirToEnsure.isEmpty() && !dirToEnsure.equals(this.baseRemotePath) && !dirToEnsure.equals("/")) {
             logger.debug("[EnsureDir] Calling internal method ensureRemoteDirectoryExistsInternal for: {}", dirToEnsure);
            ensureRemoteDirectoryExistsInternal(dirToEnsure);
        } else {
            logger.trace("[EnsureDir] Directory to ensure is null, empty, base path or root ('{}'), skipping explicit internal check/creation.", dirToEnsure);
        }
    }

    /** Internal helper to check/create a specific remote directory path (remains the same) */
    private void ensureRemoteDirectoryExistsInternal(String remoteDirPath) throws IOException {
        // (Keep the implementation with detailed logging and verification from previous step)
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
        return config.getDescription(); // Config class handles description
    }

    @Override
    public void close() throws IOException {
        // Close logic remains the same
        logger.debug("Closing SFTP Data Destination for: {}", config.getDescription());
        try { if (sftpClient != null) sftpClient.close(); }
        catch (IOException e) { logger.error("Error closing SFTPClient: {}", e.getMessage(), e); }
        finally {
             if (sshClient != null && sshClient.isConnected()) {
                try { sshClient.disconnect(); }
                catch (IOException e) { logger.error("Error disconnecting SSHClient: {}", e.getMessage(), e); }
            }
        }
        logger.info("SFTP Data Destination resources closed for: {}", config.getDescription());
    }
}