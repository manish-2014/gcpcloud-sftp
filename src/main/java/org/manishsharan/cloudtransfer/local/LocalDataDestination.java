package org.manishsharan.cloudtransfer.local;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.LocalConfig;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Objects;

/**
 * DataDestination implementation for writing to the local filesystem.
 * Always expects the configured 'path' to be a directory, even for single file transfers.
 */
public class LocalDataDestination implements DataDestination {

    private static final Logger logger = LogManager.getLogger(LocalDataDestination.class);

    private final LocalConfig config;
    private final Path basePath; // Destination is always treated as a base directory path

    public LocalDataDestination(LocalConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "LocalConfig cannot be null");
        logger.info("Initializing Local Data Destination for: {}", config.getDescription());

        // Destination configuration MUST specify a directory path, even if receiving a single file.
        if (config.isSingleFile()) {
             logger.error("Local destination cannot be configured with 'file_path'. Use 'path' for the target directory.");
             throw new IllegalArgumentException("Local destination must be configured with 'path' (directory), not 'file_path'.");
        }

        try {
            // Use getPath(), as destination must be a directory
            String pathStr = config.getPath();
            if (pathStr == null || pathStr.isBlank()){
                 throw new IllegalArgumentException("Local destination 'path' cannot be null or blank.");
            }
            this.basePath = Paths.get(pathStr).normalize();

            // Ensure base path exists and is a writable directory, create if necessary
            // (Logic moved from previous version's constructor)
            ensureBaseDirectoryExists();

        } catch (InvalidPathException e) {
             logger.error("Invalid local destination path string: {}", config.getPath(), e);
            throw new IOException("Invalid local destination path: " + config.getPath(), e);
        }
    }

     // Helper to ensure base directory exists and is writable
     private void ensureBaseDirectoryExists() throws IOException {
         if (Files.exists(this.basePath)) {
            if (!Files.isDirectory(this.basePath)) {
                throw new NotDirectoryException("Local destination path exists but is not a directory: " + this.basePath);
            }
            if (!Files.isWritable(this.basePath)) {
                throw new IOException("Local destination directory is not writable: " + this.basePath);
            }
             logger.debug("Local destination directory exists and is writable: {}", this.basePath);
        } else {
            logger.debug("Local destination directory does not exist, attempting to create: {}", this.basePath);
            try {
                Files.createDirectories(this.basePath);
                logger.info("Successfully created local destination directory: {}", this.basePath);
            } catch (IOException e) {
                logger.error("Failed to create local destination directory '{}': {}", this.basePath, e.getMessage(), e);
                throw new IOException("Failed to create local destination directory: " + this.basePath, e);
            }
        }
     }

    @Override
    public OutputStream openOutputStream(ItemInfo item) throws IOException {
        // This method is used for directory/prefix transfer mode.
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            // Should be handled by ensureDirectoryExists, but good practice to prevent opening stream
            throw new IOException("Cannot open OutputStream for a directory item: " + item.getFullRelativePath());
        }

        Path fullPath = this.basePath.resolve(item.getFullRelativePath());
        logger.debug("Opening output stream for local file: {}", fullPath);

        // Ensure parent directory exists (might be redundant if TransferService Pass 1 worked, but safe)
        ensureParentDirectoryExists(fullPath);

        try {
            return Files.newOutputStream(fullPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
             logger.error("Failed to open local file '{}' for writing: {}", fullPath, e.getMessage(), e);
             throw new IOException("Failed to open local file for writing: " + fullPath, e);
        }
    }

     @Override
    public OutputStream openSpecificOutputStream(String targetName) throws IOException, UnsupportedOperationException {
         // This method is used for single file transfer mode.
         Objects.requireNonNull(targetName, "Target file name cannot be null");
         if (targetName.isBlank() || targetName.contains("/") || targetName.contains("\\")) {
              throw new IllegalArgumentException("Invalid target file name for single file transfer: " + targetName);
         }

         Path fullPath = this.basePath.resolve(targetName);
         logger.debug("Opening specific output stream for local file: {}", fullPath);

        // Ensure parent directory exists (basePath is the parent in this case)
        ensureParentDirectoryExists(fullPath); // Will essentially re-verify basePath

         try {
             return Files.newOutputStream(fullPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
         } catch (IOException e) {
              logger.error("Failed to open specific local file '{}' for writing: {}", fullPath, e.getMessage(), e);
              throw new IOException("Failed to open specific local file for writing: " + fullPath, e);
         }
    }


    @Override
    public void ensureDirectoryExists(ItemInfo item) throws IOException {
        // This method is primarily for directory transfer mode.
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        Path dirToEnsure;

        if (item.isDirectory()) {
            dirToEnsure = this.basePath.resolve(item.getFullRelativePath());
             logger.trace("[EnsureDir] Request to ensure directory for directory item: {}", dirToEnsure);
        } else {
            // For a file, ensure its parent directory exists
            Path fullPath = this.basePath.resolve(item.getFullRelativePath());
            dirToEnsure = fullPath.getParent();
             logger.trace("[EnsureDir] Request to ensure parent directory for file item '{}': {}", item.getName(), dirToEnsure);
        }
        createDirectoriesIfNeeded(dirToEnsure);
    }

    /** Helper to create directories if they don't exist */
    private void ensureParentDirectoryExists(Path fullFilePath) throws IOException {
         if (fullFilePath == null) return;
         Path parentDir = fullFilePath.getParent();
         createDirectoriesIfNeeded(parentDir);
    }

    /** Internal helper to physically create directories */
    private void createDirectoriesIfNeeded(Path dirPath) throws IOException {
         // Check if dirPath is null (e.g., parent of a root file) or the base path itself
         if (dirPath != null && !dirPath.equals(this.basePath)) {
             if (!Files.exists(dirPath)) {
                logger.debug("Creating directory structure: {}", dirPath);
                try {
                    Files.createDirectories(dirPath);
                } catch (FileAlreadyExistsException e) {
                    if (!Files.isDirectory(dirPath)) { // Check if the existing file is not a directory
                         logger.error("Path exists but is not a directory, cannot create directory: {}", dirPath);
                         throw new IOException("Cannot create directory, path exists as a file: " + dirPath, e);
                    }
                     logger.warn("Directory already existed (likely race condition or repeated check): {}", dirPath);
                } catch (IOException e) {
                    logger.error("Failed to create directory structure '{}': {}", dirPath, e.getMessage(), e);
                    throw new IOException("Failed to create directory structure: " + dirPath, e);
                }
             } else {
                 // If it exists, verify it's a directory
                 if (!Files.isDirectory(dirPath)) {
                     logger.error("Path exists but is not a directory: {}", dirPath);
                      throw new NotDirectoryException("Path exists but is not a directory: " + dirPath);
                 }
                  logger.trace("Directory structure already exists: {}", dirPath);
             }
        } else {
             logger.trace("Directory to create is null or base path ('{}'), skipping creation.", dirPath);
        }
    }


    @Override
    public String getDescription() {
        return config.getDescription(); // Config class handles description
    }

    @Override
    public void close() throws IOException {
        logger.debug("Local Data Destination for {} closed (no-op).", config.getDescription());
    }
}