package org.manishsharan.cloudtransfer.service;

import org.manishsharan.cloudtransfer.config.LocationConfig; // Need this
import org.manishsharan.cloudtransfer.config.LocalConfig; // Need specific types for isSingleFile() etc.
import org.manishsharan.cloudtransfer.config.SftpConfig;
import org.manishsharan.cloudtransfer.config.GcpConfig;
import org.manishsharan.cloudtransfer.core.DataDestination;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths; // To extract filename
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TransferService {

    private static final Logger logger = LoggerFactory.getLogger(TransferService.class);
    private static final int BUFFER_SIZE = 8192;

    // Get source and destination configs for checking single file mode
    public void transfer(DataSource source, DataDestination destination, LocationConfig sourceConfig, LocationConfig destConfig) throws IOException {
        logger.info("Starting transfer from {} to {}", source.getDescription(), destination.getDescription());

        boolean isSourceSingleFile = isSingleFileMode(sourceConfig);
        // We assume destination is always a directory for single source file transfers for now
        // boolean isDestSingleFile = isSingleFileMode(destConfig); // Could add logic later if needed

        if (isSourceSingleFile) {
            // --- Single File Transfer Logic ---
            logger.info("Executing single file transfer mode.");
            String sourceFilePath = getEffectiveSourcePath(sourceConfig);
            if (sourceFilePath == null) {
                 throw new IOException("Could not determine source file path from configuration.");
            }
            // Extract simple filename from the source path to use as target name
            String targetFileName = extractFileName(sourceFilePath);
            if (targetFileName.isEmpty()) {
                 throw new IOException("Could not extract target filename from source path: " + sourceFilePath);
            }

            logger.info("Attempting to transfer single file '{}' to destination as '{}'", sourceFilePath, targetFileName);
            long bytesCopied = 0;
            boolean success = false;
            try (InputStream inputStream = source.openSpecificInputStream(sourceFilePath); // Use new method
                 OutputStream outputStream = destination.openSpecificOutputStream(targetFileName)) // Use new method
            {
                bytesCopied = copyStream(inputStream, outputStream);
                success = true;
                logger.info("Successfully transferred single file ({} bytes).", bytesCopied);
            } catch (UnsupportedOperationException e) {
                 logger.error("Single file transfer failed: The configured source or destination does not support specific file operations.", e);
                 throw new IOException("Configuration mismatch: Single file operation not supported by source/destination.", e);
            } catch (IOException e) {
                 logger.error("Single file transfer failed for source '{}': {}", sourceFilePath, e.getMessage(), e);
                 throw e; // Re-throw to indicate failure
            } finally {
                 // Log summary for single file
                 logger.info("Transfer summary: 1 file attempted, {} bytes transferred. Success={}", bytesCopied, success);
                 if (!success) {
                     logger.warn("Transfer failed.");
                 }
            }

        } else {
            // --- Directory/Prefix Transfer Logic (Existing Two-Pass) ---
            logger.info("Executing directory/prefix transfer mode.");
            AtomicLong fileCount = new AtomicLong(0);
            AtomicLong failedItems = new AtomicLong(0);
            AtomicLong totalBytes = new AtomicLong(0);

            List<ItemInfo> allItems;
            try (Stream<ItemInfo> itemStream = source.listItems()) { // listItems should throw if source is single file config
                allItems = itemStream.collect(Collectors.toList());
                logger.info("Found {} items (files/dirs) to process.", allItems.size());
            } catch (UnsupportedOperationException e) {
                 logger.error("Directory transfer failed: The configured source does not support listing items (is it configured as single file?).", e);
                 throw new IOException("Configuration mismatch: Source doesn't support directory listing.", e);
            } catch (Exception e) {
                logger.error("Error during item listing: {}", e.getMessage(), e);
                throw new IOException("Transfer failed during item listing.", e);
            }

            // Pass 1: Ensure directories
            logger.info("Phase 1: Ensuring all destination paths/parents exist...");
            allItems.stream()
                .forEach(item -> {
                    try {
                        logger.info("[Pass 1] Processing {}", item);
                        destination.ensureDirectoryExists(item);
                    } catch (IOException e) {
                        String failedPath = item.isDirectory() ? item.getFullRelativePath() : item.getParentRelativePath();
                        logger.error("Failed Phase 1: Could not ensure destination path exists for '{}' (related to item '{}'): {}",
                                     failedPath, item.getFullRelativePath(), e.getMessage(), e);
                        failedItems.incrementAndGet();
                    }
                });
            logger.info("Phase 1: Destination path check/creation complete.");

            if (failedItems.get() > 0) {
                 logger.error("Aborting transfer after {} failures during Phase 1 (directory creation).", failedItems.get());
                 throw new IOException("Transfer aborted due to failures during destination path creation.");
            }

            // Pass 2: Transfer files
            logger.info("Phase 2: Transferring files...");
            allItems.stream()
                .filter(item -> !item.isDirectory())
                .forEach(fileItem -> {
                     logger.debug("Processing file item: {}", fileItem.getFullRelativePath());
                    try {
                        logger.info("Transferring file: {} (Size: {} bytes)", fileItem.getFullRelativePath(), fileItem.getSize() > 0 ? fileItem.getSize() : "unknown");
                        try (InputStream inputStream = source.openInputStream(fileItem);
                             OutputStream outputStream = destination.openOutputStream(fileItem)) {
                            long bytesCopied = copyStream(inputStream, outputStream);
                            totalBytes.addAndGet(bytesCopied);
                            fileCount.incrementAndGet();
                            logger.debug("Successfully transferred {} bytes for {}", bytesCopied, fileItem.getFullRelativePath());
                        } catch (IOException e) {
                            logger.error("Failed to transfer file '{}': {}", fileItem.getFullRelativePath(), e.getMessage(), e);
                            failedItems.incrementAndGet();
                        }
                    } catch (Exception e) {
                         logger.error("Unexpected error processing file item '{}': {}", fileItem.getFullRelativePath(), e.getMessage(), e);
                          failedItems.incrementAndGet();
                    }
                });
            logger.info("Phase 2: File transfer attempts complete.");

            logger.info("Transfer summary: {} files transferred, {} items failed (cumulative). Total bytes transferred (approx): {}",
                        fileCount.get(), failedItems.get(), totalBytes.get());

            if (failedItems.get() > 0) {
                 logger.warn("Transfer completed with {} failures.", failedItems.get());
            } else {
                logger.info("Transfer completed successfully with no errors.");
            }
        } // End directory transfer else block
    }

    // --- Helper Methods ---

    /** Checks if the LocationConfig indicates single file mode */
    private boolean isSingleFileMode(LocationConfig config) {
        if (config instanceof LocalConfig lc) { // Java 16+ pattern matching
            return lc.isSingleFile();
        } else if (config instanceof SftpConfig sc) {
            return sc.isSingleFile();
        } else if (config instanceof GcpConfig gc) {
            return gc.isSingleFile();
        }
        return false; // Should not happen with known types
    }

    /** Gets the specific source file path/name from config */
    private String getEffectiveSourcePath(LocationConfig config) {
         if (config instanceof LocalConfig lc) {
            return lc.getEffectivePath(); // Returns filePath if isSingleFile()
        } else if (config instanceof SftpConfig sc) {
            return sc.getEffectivePath(); // Returns filePath if isSingleFile()
        } else if (config instanceof GcpConfig gc) {
            return gc.getEffectiveObjectName(); // Returns folder + fileName if isSingleFile()
        }
        return null;
    }

     /** Extracts the simple filename from a full path/object name */
     private String extractFileName(String fullPath) {
         if (fullPath == null || fullPath.isEmpty()) {
             return "";
         }
         // Simple approach using lastIndexOf, handles / and \ (though paths should be normalized)
         String pathNormalized = fullPath.replace('\\', '/');
         int lastSlash = pathNormalized.lastIndexOf('/');
         if (lastSlash < 0) {
             return pathNormalized; // No slashes, path is the filename
         }
         // Handle trailing slash case (shouldn't happen for files, but safety)
         if (lastSlash == pathNormalized.length() - 1) {
              // Find last slash before the trailing one
              int secondLastSlash = pathNormalized.lastIndexOf('/', lastSlash - 1);
              if (secondLastSlash < 0) return ""; // Path was just "/"?
              return pathNormalized.substring(secondLastSlash + 1, lastSlash);
         }
         return pathNormalized.substring(lastSlash + 1);
     }


    /** Efficiently copies data from InputStream to OutputStream using a buffer. */
    private long copyStream(InputStream source, OutputStream sink) throws IOException {
        long nread = 0L;
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = source.read(buf)) > 0) {
            sink.write(buf, 0, n);
            nread += n;
        }
        return nread;
    }
}