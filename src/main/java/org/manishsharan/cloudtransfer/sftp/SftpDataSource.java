package org.manishsharan.cloudtransfer.sftp;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.SftpConfig;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException; // If using this for stream errors
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * DataSource implementation for reading from an SFTP server using SSHJ.
 * Supports both directory and single file modes. Implements recursive listing for directories.
 */
public class SftpDataSource implements DataSource {

    private static final Logger logger = LogManager.getLogger(SftpDataSource.class);

    private final SftpConfig config;
    private final SSHClient sshClient;
    private final SFTPClient sftpClient;
    private final String effectiveRemotePath; // Stores either directory path or file path
    private final boolean isSingleFileMode;

    public SftpDataSource(SftpConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "SftpConfig cannot be null");
        this.isSingleFileMode = config.isSingleFile(); // Check mode from config
        this.sshClient = new SSHClient();
        logger.info("Initializing SFTP Data Source for: {}", config.getDescription());

        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        logger.warn("Using PromiscuousHostKeyVerifier for SFTP source. THIS IS INSECURE FOR PRODUCTION!");

        try {
            logger.debug("Connecting to SFTP server: {}:{}", config.getSftpServer(), config.getSftpPort());
            sshClient.connect(config.getSftpServer(), config.getSftpPort());

            logger.debug("Authenticating user '{}' with key file: {}", config.getSshUser(), config.getSshKeyPath());
            KeyProvider keyProvider = sshClient.loadKeys(config.getSshKeyPath());
            sshClient.authPublickey(config.getSshUser(), keyProvider);
            logger.info("SSH connection and authentication successful for source.");

            this.sftpClient = sshClient.newSFTPClient();
            logger.info("SFTP session established for source.");

            // Use and validate the effective path based on mode
            String pathStr = config.getEffectivePath();
            if (pathStr == null || pathStr.isBlank()) {
                 throw new IOException("Effective path/file_path cannot be blank in SFTP source config");
            }
            this.effectiveRemotePath = SftpUtils.normalizeSftpPath(pathStr);
            validateSourcePath(); // Separate validation method

        } catch (IOException e) {
            logger.error("Failed to initialize SFTP source connection: {}", e.getMessage(), e);
            close(); // Attempt cleanup
            throw e;
        }
    }

    /** Validates the configured source path based on the mode */
    private void validateSourcePath() throws IOException {
        logger.debug("Validating SFTP source path '{}' in {} mode...", effectiveRemotePath, isSingleFileMode ? "single file" : "directory");
        try {
            FileAttributes attrs = sftpClient.stat(this.effectiveRemotePath);
            FileMode.Type fileType = attrs.getMode().getType();

            if (isSingleFileMode) {
                // Single file mode: must exist and be a regular file
                if (fileType != FileMode.Type.REGULAR) {
                     throw new IOException("SFTP source configured for single file, but path exists and is not a regular file: " + this.effectiveRemotePath + " (Type: " + fileType + ")");
                }
                 logger.debug("SFTP source file path '{}' verified.", this.effectiveRemotePath);
            } else {
                 // Directory mode: must exist and be a directory
                if (fileType != FileMode.Type.DIRECTORY) {
                    throw new IOException("SFTP source configured for directory, but path exists and is not a directory: " + this.effectiveRemotePath + " (Type: " + fileType + ")");
                }
                logger.debug("SFTP source directory path '{}' verified.", this.effectiveRemotePath);
            }
        } catch (SFTPException e) {
            if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                throw new IOException("SFTP source path/file does not exist: " + this.effectiveRemotePath, e);
            } else {
                // Handle other errors like permission denied during stat
                throw new IOException("Failed to verify SFTP source path/file status: " + this.effectiveRemotePath, e);
            }
        }
    }

    @Override
    public Stream<ItemInfo> listItems() throws IOException, UnsupportedOperationException {
        if (isSingleFileMode) {
            logger.error("listItems() called but SftpDataSource is configured for single file mode.");
            throw new UnsupportedOperationException("listItems is not supported in single file mode for SftpDataSource");
        }
        // Existing recursive directory listing logic, starting from effectiveRemotePath
        logger.debug("Recursively listing items starting from remote path: {}", effectiveRemotePath);
        return listRecursiveHelper(this.effectiveRemotePath, "");
    }

    /** Recursive helper for listItems (remains the same as before) */
    private Stream<ItemInfo> listRecursiveHelper(String currentAbsolutePath, String currentRelativePath) {
        // (Keep the recursive implementation from the previous SFTP update)
        List<RemoteResourceInfo> remoteItems;
        try {
            logger.trace("Listing SFTP path: {}", currentAbsolutePath);
            remoteItems = sftpClient.ls(currentAbsolutePath);
        } catch (SFTPException e) {
            logger.error("Failed to list SFTP directory '{}': {} (Status: {}). Skipping.", currentAbsolutePath, e.getMessage(), e.getStatusCode());
            return Stream.empty();
        } catch (IOException e) {
             logger.error("IOException listing SFTP directory '{}': {}. Skipping.", currentAbsolutePath, e.getMessage());
             return Stream.empty();
        }
        return remoteItems.stream()
            .filter(item -> !item.getName().equals(".") && !item.getName().equals(".."))
            .flatMap(item -> {
                try {
                    String itemName = item.getName();
                    FileAttributes attributes = item.getAttributes();
                    boolean isDirectory = attributes.getMode().getType() == FileMode.Type.DIRECTORY;
                    long size = isDirectory ? -1 : attributes.getSize();
                    ItemInfo currentItemInfo = new ItemInfo(currentRelativePath, itemName, isDirectory, size);
                    if (isDirectory) {
                        String nextAbsolutePath = SftpUtils.buildRemotePath(currentAbsolutePath, itemName);
                        String nextRelativePath = currentRelativePath.isEmpty() ? itemName : SftpUtils.buildRemotePath(currentRelativePath, itemName);
                        return Stream.concat(Stream.of(currentItemInfo), listRecursiveHelper(nextAbsolutePath, nextRelativePath));
                    } else {
                        return Stream.of(currentItemInfo);
                    }
                } catch (Exception e) {
                    logger.warn("Error processing SFTP item '{}' in path '{}', skipping item: {}", item.getName(), currentAbsolutePath, e.getMessage());
                     return Stream.empty();
                }
            });
    }


    @Override
    public InputStream openInputStream(ItemInfo item) throws IOException, UnsupportedOperationException {
         if (isSingleFileMode) {
             logger.error("openInputStream() called but SftpDataSource is configured for single file mode.");
             throw new UnsupportedOperationException("openInputStream is not supported in single file mode for SftpDataSource");
         }

        // Existing logic for reading files found via listItems
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open InputStream for a directory: " + item.getFullRelativePath());
        }
        // Build the full absolute path from the base *directory* path
        String fullRemotePath = SftpUtils.buildRemotePath(this.effectiveRemotePath, item.getFullRelativePath());
        logger.debug("Opening input stream for remote file: {}", fullRemotePath);
        return openRemoteFileInputStream(fullRemotePath); // Use helper
    }

     @Override
    public InputStream openSpecificInputStream(String sourceIdentifier) throws IOException, UnsupportedOperationException {
        if (!isSingleFileMode) {
            logger.error("openSpecificInputStream() called but SftpDataSource is configured for directory mode.");
            throw new UnsupportedOperationException("openSpecificInputStream is only supported in directory mode for SftpDataSource");
        }
        // In single file mode, sourceIdentifier *should* match effectiveRemotePath after normalization
        String normalizedSourceIdentifier = SftpUtils.normalizeSftpPath(sourceIdentifier);
        if (!normalizedSourceIdentifier.equals(this.effectiveRemotePath)) {
             logger.error("Requested specific file '{}' does not match configured file path '{}'", sourceIdentifier, this.effectiveRemotePath);
             throw new IllegalArgumentException("Requested specific file does not match configuration");
        }

        logger.debug("Opening specific input stream for remote file: {}", this.effectiveRemotePath);
        // Validation happened in constructor, proceed to open
        return openRemoteFileInputStream(this.effectiveRemotePath); // Use helper
    }

    /** Helper method to open a remote file input stream */
    private InputStream openRemoteFileInputStream(String absolutePath) throws IOException {
         try {
            RemoteFile remoteFile = sftpClient.open(absolutePath, EnumSet.of(OpenMode.READ));
            return remoteFile.new RemoteFileInputStream();
        } catch (SFTPException e) {
            logger.error("Failed to open SFTP file '{}' for reading: {} (Status: {})",
                         absolutePath, e.getMessage(), e.getStatusCode(), e);
             if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                 throw new IOException("Remote file not found: " + absolutePath, e);
             } else if (e.getStatusCode() == Response.StatusCode.PERMISSION_DENIED) {
                  throw new IOException("Permission denied opening remote file: " + absolutePath, e);
             } else {
                 throw new IOException("Failed to open remote file: " + absolutePath, e);
             }
        }
    }

    @Override
    public String getDescription() {
        return config.getDescription(); // Config class handles description based on mode
    }

    @Override
    public void close() throws IOException {
        // Close logic remains the same
        logger.debug("Closing SFTP Data Source for: {}", config.getDescription());
        try {
            if (sftpClient != null) sftpClient.close();
        } catch (IOException e) { logger.error("Error closing SFTPClient: {}", e.getMessage(), e); }
        finally {
            if (sshClient != null && sshClient.isConnected()) {
                try { sshClient.disconnect(); }
                catch (IOException e) { logger.error("Error disconnecting SSHClient: {}", e.getMessage(), e); }
            }
        }
         logger.info("SFTP Data Source resources closed for: {}", config.getDescription());
    }
}