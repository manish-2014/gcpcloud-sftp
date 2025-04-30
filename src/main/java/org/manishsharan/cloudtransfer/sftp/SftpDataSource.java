package org.manishsharan.cloudtransfer.sftp;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
// Removed unused transport/auth imports as client creation is delegated
// import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
// import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manishsharan.cloudtransfer.config.SftpConfig;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.core.ItemInfo;
// Import the provider interface
import org.manishsharan.cloudtransfer.providers.SftpClientProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * DataSource implementation for reading from an SFTP server using SSHJ.
 * Supports both directory and single file modes. Uses SftpClientProvider for client creation.
 */
public class SftpDataSource implements DataSource {

    private static final Logger logger = LoggerFactory.getLogger(SftpDataSource.class);

    private final SftpConfig config;
    private final SftpClientProvider clientProvider; // Added provider field
    private final SSHClient sshClient; // Store the client obtained from provider
    private final SFTPClient sftpClient;
    private final String effectiveRemotePath;
    private final boolean isSingleFileMode;

    // Constructor now accepts SftpClientProvider
    public SftpDataSource(SftpConfig config, SftpClientProvider clientProvider) throws IOException {
        this.config = Objects.requireNonNull(config, "SftpConfig cannot be null");
        this.clientProvider = Objects.requireNonNull(clientProvider, "SftpClientProvider cannot be null");
        this.isSingleFileMode = config.isSingleFile();
        logger.info("Initializing SFTP Data Source for: {}", config.getDescription());

        SSHClient tempSshClient = null; // Temporary client reference for cleanup on failure
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
            logger.info("SFTP session established for source.");

            String pathStr = config.getEffectivePath();
            if (pathStr == null || pathStr.isBlank()) {
                 throw new IOException("Effective path/file_path cannot be blank in SFTP source config");
            }
            this.effectiveRemotePath = SftpUtils.normalizeSftpPath(pathStr);
            validateSourcePath(); // Validate path using the obtained client

        } catch (IOException e) {
            logger.error("Failed to initialize SFTP source: {}", e.getMessage(), e);
            // Ensure cleanup if provider succeeded but subsequent steps failed
            if (tempSshClient != null) {
                 try {
                     if (tempSshClient.isConnected()) tempSshClient.disconnect();
                 } catch (IOException closeEx) {
                     logger.error("Error closing SSH client during initialization cleanup", closeEx);
                 }
            }
            // Assign null if not successfully initialized (helps close() method)
            // this.sshClient = null; // Not needed as it's final, error thrown before assignment
            // this.sftpClient = null;
            throw e; // Re-throw original exception
        } catch (Exception e) { // Catch unexpected provider errors
             logger.error("Unexpected error during SFTP source initialization via provider: {}", e.getMessage(), e);
             if (tempSshClient != null) { /* cleanup */ }
             throw new IOException("Unexpected error initializing SFTP source: " + e.getMessage(), e);
        }
    }

    /** Validates the configured source path based on the mode */
    private void validateSourcePath() throws IOException {
        // (Validation logic remains the same as before)
        logger.debug("Validating SFTP source path '{}' in {} mode...", effectiveRemotePath, isSingleFileMode ? "single file" : "directory");
        try {
            FileAttributes attrs = sftpClient.stat(this.effectiveRemotePath);
            FileMode.Type fileType = attrs.getMode().getType();

            if (isSingleFileMode) {
                if (fileType != FileMode.Type.REGULAR) {
                     throw new IOException("SFTP source configured for single file, but path exists and is not a regular file: " + this.effectiveRemotePath + " (Type: " + fileType + ")");
                }
                 logger.debug("SFTP source file path '{}' verified.", this.effectiveRemotePath);
            } else {
                if (fileType != FileMode.Type.DIRECTORY) {
                    throw new IOException("SFTP source configured for directory, but path exists and is not a directory: " + this.effectiveRemotePath + " (Type: " + fileType + ")");
                }
                logger.debug("SFTP source directory path '{}' verified.", this.effectiveRemotePath);
            }
        } catch (SFTPException e) {
            if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                throw new IOException("SFTP source path/file does not exist: " + this.effectiveRemotePath, e);
            } else {
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
        logger.debug("Recursively listing items starting from remote path: {}", effectiveRemotePath);
        return listRecursiveHelper(this.effectiveRemotePath, "");
    }

    /** Recursive helper for listItems (remains the same) */
    private Stream<ItemInfo> listRecursiveHelper(String currentAbsolutePath, String currentRelativePath) {
        // (Keep the recursive implementation from the previous SFTP update)
        List<RemoteResourceInfo> remoteItems;
        try {
            logger.trace("Listing SFTP path: {}", currentAbsolutePath);
            remoteItems = sftpClient.ls(currentAbsolutePath);
        } catch (IOException e) { // Catch SFTPException as well
             logger.error("IOException listing SFTP directory '{}': {}. Skipping.",
                         currentAbsolutePath, e.getMessage());
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
             logger.error("openInputStream(ItemInfo) called but SftpDataSource is configured for single file mode.");
             throw new UnsupportedOperationException("openInputStream(ItemInfo) is not supported in single file mode for SftpDataSource");
         }
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open InputStream for a directory: " + item.getFullRelativePath());
        }
        // Build the full absolute path from the base *directory* path
        String fullRemotePath = SftpUtils.buildRemotePath(this.effectiveRemotePath, item.getFullRelativePath());
        logger.debug("Opening input stream for remote file: {}", fullRemotePath);
        return openRemoteFileInputStream(fullRemotePath);
    }

     @Override
    public InputStream openSpecificInputStream(String sourceIdentifier) throws IOException, UnsupportedOperationException {
        if (!isSingleFileMode) {
            logger.error("openSpecificInputStream() called but SftpDataSource is configured for directory mode.");
            throw new UnsupportedOperationException("openSpecificInputStream is only supported in directory mode for SftpDataSource");
        }
        String normalizedSourceIdentifier = SftpUtils.normalizeSftpPath(sourceIdentifier);
        if (!normalizedSourceIdentifier.equals(this.effectiveRemotePath)) {
             logger.error("Requested specific file '{}' does not match configured file path '{}'", sourceIdentifier, this.effectiveRemotePath);
             throw new IllegalArgumentException("Requested specific file does not match configuration");
        }
        logger.debug("Opening specific input stream for remote file: {}", this.effectiveRemotePath);
        return openRemoteFileInputStream(this.effectiveRemotePath);
    }

    /** Helper method to open a remote file input stream */
    private InputStream openRemoteFileInputStream(String absolutePath) throws IOException {
         try {
            RemoteFile remoteFile = sftpClient.open(absolutePath, EnumSet.of(OpenMode.READ));
            return remoteFile.new RemoteFileInputStream();
        } catch (SFTPException e) {
            // (Error handling remains the same)
            logger.error("Failed to open SFTP file '{}' for reading: {} (Status: {})", absolutePath, e.getMessage(), e.getStatusCode(), e);
             if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) throw new IOException("Remote file not found: " + absolutePath, e);
             else if (e.getStatusCode() == Response.StatusCode.PERMISSION_DENIED) throw new IOException("Permission denied opening remote file: " + absolutePath, e);
             else throw new IOException("Failed to open remote file: " + absolutePath, e);
        }
    }

    @Override
    public String getDescription() {
        return config.getDescription();
    }

    @Override
    public void close() throws IOException {
        // Close logic remains the same - closes clients obtained from provider
        logger.debug("Closing SFTP Data Source for: {}", config.getDescription());
        IOException clientEx = null;
        try {
            if (sftpClient != null) sftpClient.close();
        } catch (IOException e) {
            logger.error("Error closing SFTPClient: {}", e.getMessage(), e);
            clientEx = e; // Store first exception
        } finally {
            if (sshClient != null && sshClient.isConnected()) {
                try { sshClient.disconnect(); }
                catch (IOException e) {
                     logger.error("Error disconnecting SSHClient: {}", e.getMessage(), e);
                     if (clientEx == null) clientEx = e; // Store if no previous exception
                     else clientEx.addSuppressed(e); // Add as suppressed
                }
            }
        }
         logger.info("SFTP Data Source resources closed for: {}", config.getDescription());
         if (clientEx != null) throw clientEx; // Re-throw stored exception if any occurred
    }
}
