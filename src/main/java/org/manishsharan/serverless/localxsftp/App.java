package org.manishsharan.serverless.localxsftp;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.sftp.*;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier; // For simplicity, replace in production
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
// --- ADDED IMPORT ---
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;


/**
 * SFTP client application using SSHJ.
 * Performs various SFTP operations (upload/download file/folder, list remote)
 * based on a JSON configuration file provided via command-line argument.
 */
public class App {

    private static final Logger logger = LogManager.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("SFTP Client Started.");

        // --- Read Configuration ---
        if (args.length == 0) {
            logger.error("Configuration file path must be provided as a command-line argument.");
            System.err.println("Usage: java -jar <your-jar-file>.jar /path/to/config.json");
            return;
        }
        String configFilePath = args[0];
        logger.info("Attempting to load configuration from: {}", configFilePath);

        SftpConfig config = loadConfig(configFilePath);
        if (config == null) {
            // Error logged in loadConfig
            return;
        }

        try {
            config.validate(); // Validate required fields based on operation
            logger.info("Configuration loaded and validated: {}", config);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid configuration: {}", e.getMessage());
            return;
        }


        // --- Establish SSH Connection and Perform Operation ---
        // Using try-with-resources ensures the client is closed automatically
        try (SSHClient sshClient = new SSHClient()) {
            setupSshConnection(sshClient, config); // Connect and authenticate

            // Using try-with-resources ensures the SFTP client is closed
            try (SFTPClient sftpClient = sshClient.newSFTPClient()) {
                logger.info("SFTP Session established.");

                // --- Execute the requested operation ---
                switch (config.getOperation()) {
                    case UPLOAD_FILE:
                        handleUploadFile(sftpClient, config);
                        break;
                    case UPLOAD_FOLDER:
                        handleUploadFolder(sftpClient, config);
                        break;
                    case DOWNLOAD_FILE:
                        handleDownloadFile(sftpClient, config);
                        break;
                    case DOWNLOAD_FOLDER:
                        handleDownloadFolder(sftpClient, config);
                        break;
                    case LIST_REMOTE:
                        handleListRemote(sftpClient, config.getRemotePath(), ""); // Start listing from remote path
                        break;
                    default:
                        // Should be caught by validation, but defensive check
                        logger.error("Unsupported operation specified: {}", config.getOperation());
                        break;
                }
            } catch (IOException e) { // Catch SFTP specific errors
                 if (e instanceof SFTPException) {
                    SFTPException sftpEx = (SFTPException) e;
                    logger.error("SFTP Error: {} (Status Code: {})", sftpEx.getMessage(), sftpEx.getStatusCode(), sftpEx);
                 } else {
                    logger.error("SFTP session error: {}", e.getMessage(), e);
                 }
            }

        } catch (IOException e) { // Catch connection/authentication errors
            logger.error("SSH connection or authentication failed: {}", e.getMessage(), e);
        } catch (Exception e) { // Catch unexpected errors during setup or operation handling
             logger.error("An unexpected error occurred: {}", e.getMessage(), e);
        } finally {
            logger.info("SFTP Client Finished.");
        }
    }

    /**
     * Sets up the SSH connection and authenticates the user.
     * @param sshClient The SSHClient instance to configure.
     * @param config The SftpConfig containing connection details.
     * @throws IOException If connection or authentication fails.
     */
    private static void setupSshConnection(SSHClient sshClient, SftpConfig config) throws IOException {
         // WARNING: PromiscuousVerifier accepts *any* host key. Replace in production.
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        logger.warn("Using PromiscuousHostKeyVerifier. Replace with known_hosts verification in production!");

        logger.info("Connecting to SFTP server: {}:{}", config.getSftpServer(), config.getSftpPort());
        sshClient.connect(config.getSftpServer(), config.getSftpPort());
        logger.info("Connected successfully.");

        logger.info("Authenticating user '{}' with key file: {}", config.getSshUser(), config.getSshKey());
        // Assumes the key file is not protected by a passphrase.
        // TODO: Add support for passphrase-protected keys if needed.
        KeyProvider keys = sshClient.loadKeys(config.getSshKey());
        sshClient.authPublickey(config.getSshUser(), keys);
        logger.info("Authentication successful.");
    }

    // --- Operation Handlers ---

    /**
     * Handles uploading a single file.
     */
    private static void handleUploadFile(SFTPClient sftpClient, SftpConfig config) throws IOException {
        Path localPath = Paths.get(config.getLocalPath());
        if (!Files.exists(localPath) || !Files.isRegularFile(localPath)) {
            logger.error("Local file to upload does not exist or is not a regular file: {}", localPath);
            // --- CORRECTED EXCEPTION ---
            throw new FileNotFoundException("Local file not found or not a regular file: " + localPath);
        }

        String remotePathStr = normalizeSftpPath(config.getRemotePath());
        // If remote path ends with /, assume it's a directory and append filename
        if (remotePathStr.endsWith("/") || remotePathStr.equals("/")) {
             // Handle root case explicitly
             if (remotePathStr.equals("/")) {
                  remotePathStr = "/" + localPath.getFileName().toString();
             } else {
                  remotePathStr = remotePathStr + localPath.getFileName().toString();
             }
            logger.warn("Remote path appears to be a directory ('{}'). Appending local filename: '{}'", config.getRemotePath(), remotePathStr);
        }
        else{
            logger.warn("Remote path does not end with '/'. Assuming it's a file path: '{}'", remotePathStr);
            remotePathStr = remotePathStr +"/" + localPath.getFileName().toString();
            
        }
         String remoteDir = getParentPath(remotePathStr);


        logger.info("Attempting to upload file '{}' to remote path '{}'", localPath, remotePathStr);
        ensureRemoteDirectoryExists(sftpClient, remoteDir); // Ensure parent directory exists

        sftpClient.put(config.getLocalPath(), remotePathStr);
        logger.info("File uploaded successfully!");
    }

    /**
     * Handles uploading a local folder recursively.
     */
    private static void handleUploadFolder(SFTPClient sftpClient, SftpConfig config) throws IOException {
        Path localFolderPath = Paths.get(config.getLocalPath());
        if (!Files.isDirectory(localFolderPath)) {
            logger.error("Local path to upload is not a directory: {}", localFolderPath);
            throw new NotDirectoryException("Local path is not a directory: " + localFolderPath);
        }

        String baseRemotePath = normalizeSftpPath(config.getRemotePath());
        logger.info("Attempting to upload folder '{}' to remote base path '{}'", localFolderPath, baseRemotePath);

        // Ensure the base remote directory exists
        ensureRemoteDirectoryExists(sftpClient, baseRemotePath);

        try (Stream<Path> stream = Files.walk(localFolderPath)) {
            stream.forEach(source -> {
                try {
                    Path relativePath = localFolderPath.relativize(source);
                    // Skip the root folder itself for path building if relativePath is empty
                    if (relativePath.toString().isEmpty()) {
                        return; // Continue to next item in stream
                    }
                    String remoteTargetPath = buildRemotePath(baseRemotePath, relativePath.toString());

                    if (Files.isDirectory(source)) {
                        logger.debug("Creating remote directory: {}", remoteTargetPath);
                        ensureRemoteDirectoryExists(sftpClient, remoteTargetPath); // Use ensure for idempotency
                    } else if (Files.isRegularFile(source)) {
                        logger.info("Uploading file '{}' to '{}'", source, remoteTargetPath);
                        sftpClient.put(source.toString(), remoteTargetPath);
                    } else {
                        logger.warn("Skipping non-regular file/directory: {}", source);
                    }
                } catch (IOException e) {
                    // Log the error but try to continue with other files/folders
                    logger.error("Failed processing path '{}': {}", source, e.getMessage(), e);
                    // Consider adding a mechanism to stop on first error if needed
                }
            });
        }
        logger.info("Folder upload process completed for '{}'. Check logs for any individual errors.", localFolderPath);
    }


    /**
     * Handles downloading a single file.
     */
    private static void handleDownloadFile(SFTPClient sftpClient, SftpConfig config) throws IOException {
        String remotePathStr = normalizeSftpPath(config.getRemotePath());
        Path localPath = Paths.get(config.getLocalPath());

        // Check if remote file exists and is a file
        FileAttributes attrs;
        try {
             attrs = sftpClient.stat(remotePathStr);
             // --- CORRECTED CHECK ---
             if (attrs.getType() != FileMode.Type.REGULAR) {
                  logger.error("Remote path exists but is not a regular file: {}", remotePathStr);
                  throw new SFTPException(Response.StatusCode.FAILURE, "Remote path is not a regular file: " + remotePathStr);
             }
        } catch (SFTPException e) {
             if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                logger.error("Remote file does not exist: {}", remotePathStr);
             } else {
                 logger.error("Failed to check status of remote file '{}': {}", remotePathStr, e.getMessage(), e);
             }
             throw e; // Re-throw the exception
        }


        // Ensure local directory exists
        Path localDir = localPath.getParent();
        if (localDir != null) {
            ensureLocalDirectoryExists(localDir);
        } else {
            logger.warn("Local path has no parent directory (likely relative path in current dir): {}", localPath);
        }

        logger.info("Attempting to download remote file '{}' to local path '{}'", remotePathStr, localPath);
        sftpClient.get(remotePathStr, config.getLocalPath()); // Use original local path string for FileSystemAccess
        logger.info("File downloaded successfully!");
    }

    /**
     * Handles downloading a remote folder recursively.
     */
    private static void handleDownloadFolder(SFTPClient sftpClient, SftpConfig config) throws IOException {
        String baseRemotePath = normalizeSftpPath(config.getRemotePath());
        Path baseLocalPath = Paths.get(config.getLocalPath());

        // Check if remote path exists and is a directory
         FileAttributes attrs;
        try {
             attrs = sftpClient.stat(baseRemotePath);
             // --- CORRECTED CHECK ---
             if (attrs.getType() != FileMode.Type.DIRECTORY) {
                  logger.error("Remote path exists but is not a directory: {}", baseRemotePath);
                  throw new SFTPException(Response.StatusCode.FAILURE, "Remote path is not a directory: " + baseRemotePath);
             }
        } catch (SFTPException e) {
             if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                logger.error("Remote directory does not exist: {}", baseRemotePath);
             } else {
                 logger.error("Failed to check status of remote directory '{}': {}", baseRemotePath, e.getMessage(), e);
             }
             throw e; // Re-throw the exception
        }


        logger.info("Attempting recursive download from remote folder '{}' to local folder '{}'", baseRemotePath, baseLocalPath);
        ensureLocalDirectoryExists(baseLocalPath); // Ensure base local directory exists
        recursiveDownload(sftpClient, baseRemotePath, baseLocalPath);
        logger.info("Folder download process completed for '{}'. Check logs for any individual errors.", baseRemotePath);
    }

    /**
     * Recursively downloads files and directories from remote path to local path.
     * @param sftpClient The SFTPClient instance.
     * @param remotePath Current remote directory to process.
     * @param localPath Corresponding local directory to download into.
     * @throws IOException If SFTP or local file operations fail.
     */
    private static void recursiveDownload(SFTPClient sftpClient, String remotePath, Path localPath) throws IOException {
        List<RemoteResourceInfo> items = sftpClient.ls(remotePath);
        for (RemoteResourceInfo item : items) {
            String itemName = item.getName();
            if (itemName.equals(".") || itemName.equals("..")) {
                continue; // Skip self and parent directory entries
            }

            String currentRemotePath = buildRemotePath(remotePath, itemName);
            Path currentLocalPath = localPath.resolve(itemName);

            // Note: RemoteResourceInfo *does* have isDirectory() and isRegularFile()
            if (item.isDirectory()) {
                logger.debug("Processing remote directory: {}", currentRemotePath);
                ensureLocalDirectoryExists(currentLocalPath);
                recursiveDownload(sftpClient, currentRemotePath, currentLocalPath); // Recurse
            } else if (item.isRegularFile()) {
                logger.info("Downloading remote file '{}' to '{}'", currentRemotePath, currentLocalPath);
                 try {
                    sftpClient.get(currentRemotePath, currentLocalPath.toString());
                 } catch (IOException e) {
                    logger.error("Failed to download file '{}': {}", currentRemotePath, e.getMessage(), e);
                    // Decide whether to continue or re-throw
                    // throw e; // Uncomment to stop on first download error
                 }
            } else {
                logger.warn("Skipping non-regular/non-directory remote item: {}", currentRemotePath);
            }
        }
    }


    /**
     * Handles listing files and folders recursively on the remote server.
     * @param sftpClient The SFTPClient instance.
     * @param remotePath The current remote directory path to list.
     * @param indent Indentation string for pretty printing.
     */
    private static void handleListRemote(SFTPClient sftpClient, String remotePath, String indent) throws IOException {
        remotePath = normalizeSftpPath(remotePath);
        logger.info("{}Listing contents of: {}", indent, remotePath);

        // Check if remote path exists and is a directory
         try {
             FileAttributes attrs = sftpClient.stat(remotePath);
             // --- CORRECTED CHECK ---
             if (attrs.getType() != FileMode.Type.DIRECTORY) {
                  logger.error("Remote path exists but is not a directory: {}", remotePath);
                  throw new SFTPException(Response.StatusCode.FAILURE, "Remote path is not a directory: " + remotePath);
             }
        } catch (SFTPException e) {
             if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                logger.error("Remote directory does not exist: {}", remotePath);
             } else {
                 logger.error("Failed to check status of remote directory '{}': {}", remotePath, e.getMessage(), e);
             }
             throw e; // Re-throw the exception
        }


        List<RemoteResourceInfo> items;
        try {
            items = sftpClient.ls(remotePath);
        } catch (SFTPException e) {
            logger.error("{}Failed to list directory '{}': {} (Status Code: {})", indent, remotePath, e.getMessage(), e.getStatusCode());
            return; // Stop listing this branch on error
        }

        for (RemoteResourceInfo item : items) {
            String itemName = item.getName();
            if (itemName.equals(".") || itemName.equals("..")) {
                continue;
            }
            String fullItemPath = buildRemotePath(remotePath, itemName); // Use helper for consistency
            // Note: RemoteResourceInfo *does* have isDirectory() and isRegularFile()
            if (item.isDirectory()) {
                logger.info("{}[DIR]  {}", indent + "  ", fullItemPath);
                handleListRemote(sftpClient, fullItemPath, indent + "  "); // Recurse
            } else if (item.isRegularFile()) {
                logger.info("{}[FILE] {}", indent + "  ", fullItemPath);
            } else {
                 // Handle links, etc. if necessary
                 logger.info("{}[OTHER] {}", indent + "  ", fullItemPath);
            }
        }
    }

    // --- Helper Methods --- (No changes below this line needed for these errors)

    /**
     * Loads configuration from the specified JSON file path.
     * @param configFilePath The path to the configuration JSON file.
     * @return SftpConfig object or null if loading fails.
     */
    private static SftpConfig loadConfig(String configFilePath) {
        ObjectMapper mapper = new ObjectMapper();
        Path path;
        try {
             path = Paths.get(configFilePath);
             if (!Files.exists(path) || !Files.isReadable(path)) {
                 logger.error("Configuration file does not exist or is not readable: {}", configFilePath);
                 return null;
             }
        } catch (InvalidPathException e) {
            logger.error("Invalid configuration file path provided: '{}'", configFilePath, e);
            return null;
        }

        try (InputStream is = Files.newInputStream(path)) {
            return mapper.readValue(is, SftpConfig.class);
        } catch (NoSuchFileException e) { // Should be caught above, but good practice
             logger.error("Configuration file not found: '{}'", configFilePath, e);
             return null;
        } catch (IOException e) { // Catches JsonProcessingException too
            logger.error("Failed to read or parse configuration file '{}': {}", configFilePath, e.getMessage(), e);
            return null;
        } catch (Exception e) {
             logger.error("An unexpected error occurred while loading configuration file '{}': {}", configFilePath, e.getMessage(), e);
             return null;
        }
    }

    /**
     * Normalizes an SFTP path string.
     * Ensures it starts with '/', removes trailing '/', and defaults to "/" if null/empty/whitespace.
     * Replaces backslashes with forward slashes.
     * @param path The raw path string.
     * @return A normalized SFTP path string (e.g., "/path/to/folder" or "/path/to/file.txt" or "/").
     */
     private static String normalizeSftpPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "/"; // Default to root
        }
        String normalized = path.trim().replace('\\', '/');
        // Remove trailing slash *only* if it's not the root path itself
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
         // Ensure it starts with a slash if it's not empty after trimming potential trailing slashes
        if (!normalized.isEmpty() && !normalized.startsWith("/")) {
             normalized = "/" + normalized;
        }
         // Handle case where input was just "/" -> becomes "" -> should be "/"
        if (normalized.isEmpty() && path.trim().equals("/")) {
             return "/";
        }
        // Handle case where input was empty/whitespace -> becomes "" -> should be "/"
         if (normalized.isEmpty()) {
             return "/";
         }

        return normalized;
    }

     /**
     * Gets the parent directory path from a normalized SFTP path.
     * Returns "/" if the path is in the root directory or is the root itself.
     * @param normalizedPath A normalized SFTP path (file or directory).
     * @return The normalized parent directory path.
     */
     private static String getParentPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.equals("/")) {
            return "/"; // Parent of root is root
        }
        // Find the last '/'
        int lastSlash = normalizedPath.lastIndexOf('/');
        if (lastSlash == 0) {
            // Item is in the root directory (e.g., "/file.txt"), parent is "/"
            return "/";
        } else if (lastSlash > 0) {
            // Item is in a subdirectory (e.g., "/dir/file.txt"), parent is "/dir"
            return normalizedPath.substring(0, lastSlash);
        } else {
             // Should not happen for a normalized path starting with '/', but handle defensively
            logger.warn("Could not determine parent path for '{}', returning root '/'", normalizedPath);
            return "/";
        }
    }


    /**
     * Builds a combined remote path, ensuring proper slash separation.
     * @param basePath The normalized base remote path (directory).
     * @param relativePath The relative path component (file or subdirectory). Should use '/' separators.
     * @return The combined, normalized path.
     */
    private static String buildRemotePath(String basePath, String relativePath) {
        basePath = normalizeSftpPath(basePath); // Ensure base is normalized
        // Sanitize relative path slightly
        String cleanRelative = relativePath.replace('\\', '/'); // Ensure forward slashes
        while (cleanRelative.startsWith("/")) {
            cleanRelative = cleanRelative.substring(1); // Remove leading slashes
        }
        while (cleanRelative.endsWith("/")) {
             cleanRelative = cleanRelative.substring(0, cleanRelative.length() -1); // Remove trailing slashes
        }

        if (cleanRelative.isEmpty()) return basePath; // Nothing to append

        if (basePath.equals("/")) {
            return "/" + cleanRelative;
        } else {
            return basePath + "/" + cleanRelative;
        }
    }


    /**
     * Checks if a remote directory exists and creates it recursively if it doesn't.
     * @param sftpClient The active SFTPClient.
     * @param remoteDirPath The normalized path of the directory to check/create.
     * @throws IOException If checking or creating the directory fails unexpectedly.
     */
    private static void ensureRemoteDirectoryExists(SFTPClient sftpClient, String remoteDirPath) throws IOException {
         remoteDirPath = normalizeSftpPath(remoteDirPath); // Ensure path is normalized

        if (remoteDirPath.equals("/")) {
             logger.debug("Destination is root directory, skipping directory check/creation.");
             return; // Cannot create root
        }
        try {
            FileAttributes attrs = sftpClient.stat(remoteDirPath);
            // --- CORRECTED CHECK ---
            if (attrs.getType() == FileMode.Type.DIRECTORY) {
                 logger.debug("Remote directory '{}' already exists.", remoteDirPath);
            } else {
                 // Path exists but is not a directory!
                 logger.error("Remote path '{}' exists but is not a directory.", remoteDirPath);
                 throw new IOException("Remote path exists but is not a directory: " + remoteDirPath);
            }
        } catch (SFTPException e) {
            if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
                logger.info("Remote directory '{}' does not exist. Attempting to create recursively.", remoteDirPath);
                try {
                    sftpClient.mkdirs(remoteDirPath); // Creates parent directories as needed
                    logger.info("Successfully created remote directory '{}'.", remoteDirPath);
                } catch (IOException createEx) {
                    logger.error("Failed to create remote directory '{}': {}", remoteDirPath, createEx.getMessage(), createEx);
                    throw createEx; // Re-throw creation error
                }
            } else {
                // Different SFTP error during stat (e.g., permission denied)
                logger.error("Failed to check status of remote directory '{}': {}", remoteDirPath, e.getMessage(), e);
                throw e; // Re-throw original SFTPException
            }
        }
    }

     /**
     * Ensures the specified local directory path exists, creating it if necessary.
     * @param localDirPath The Path object representing the local directory.
     * @throws IOException If the directory cannot be created or if a file exists at the path.
     */
     private static void ensureLocalDirectoryExists(Path localDirPath) throws IOException {
        if (Files.exists(localDirPath)) {
            if (!Files.isDirectory(localDirPath)) {
                logger.error("Local path exists but is not a directory: {}", localDirPath);
                throw new FileAlreadyExistsException("Local path exists but is not a directory: " + localDirPath);
            } else {
                logger.debug("Local directory already exists: {}", localDirPath);
            }
        } else {
            logger.info("Creating local directory: {}", localDirPath);
            Files.createDirectories(localDirPath); // Create parent directories as needed
             logger.info("Successfully created local directory: {}", localDirPath);
        }
    }
}