package org.manishsharan.cloudtransfer.local;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.manishsharan.cloudtransfer.config.LocalConfig;
import org.manishsharan.cloudtransfer.core.DataSource;
import org.manishsharan.cloudtransfer.core.ItemInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * DataSource implementation for reading from the local filesystem.
 * Supports both directory and single file modes.
 */
public class LocalDataSource implements DataSource {

    private static final Logger logger = LogManager.getLogger(LocalDataSource.class);

    private final LocalConfig config;
    private final Path effectivePath; // Stores either directory path or file path
    private final boolean isSingleFileMode;

    public LocalDataSource(LocalConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "LocalConfig cannot be null");
        this.isSingleFileMode = config.isSingleFile(); // Check mode from config
        logger.info("Initializing Local Data Source for: {}", config.getDescription());

        try {
            String pathStr = config.getEffectivePath(); // Get the relevant path (dir or file)
            this.effectivePath = Paths.get(pathStr).normalize();

            // Validate base path based on mode
            if (!Files.exists(this.effectivePath)) {
                throw new NoSuchFileException("Local source path/file does not exist: " + this.effectivePath);
            }

            if (isSingleFileMode) {
                // Single file mode: Ensure it's a file and readable
                if (!Files.isRegularFile(this.effectivePath)) {
                    throw new IOException("Local source path is not a regular file: " + this.effectivePath);
                }
                 if (!Files.isReadable(this.effectivePath)) {
                    throw new IOException("Local source file is not readable: " + this.effectivePath);
                 }
                 logger.debug("Local source file validated: {}", this.effectivePath);
            } else {
                // Directory mode: Ensure it's a directory and readable
                if (!Files.isDirectory(this.effectivePath)) {
                    throw new NotDirectoryException("Local source path is not a directory: " + this.effectivePath);
                }
                if (!Files.isReadable(this.effectivePath)) {
                    throw new IOException("Local source path is not readable: " + this.effectivePath);
                }
                logger.debug("Local source path validated: {}", this.effectivePath);
            }

        } catch (InvalidPathException e) {
            logger.error("Invalid local source path string: {}", config.getEffectivePath(), e);
            throw new IOException("Invalid local source path: " + config.getEffectivePath(), e);
        }
    }

    @Override
    public Stream<ItemInfo> listItems() throws IOException, UnsupportedOperationException {
        if (isSingleFileMode) {
             logger.error("listItems() called but LocalDataSource is configured for single file mode.");
            throw new UnsupportedOperationException("listItems is not supported in single file mode for LocalDataSource");
        }

        // Existing recursive directory listing logic
        logger.debug("Recursively listing items starting from local path: {}", effectivePath); // Use effectivePath
        try {
            Stream<Path> pathStream = Files.walk(effectivePath);
            return pathStream
                .onClose(() -> pathStream.close()) // Ensure underlying stream is closed
                .filter(path -> !path.equals(effectivePath)) // Filter out base path
                .map(descendantPath -> {
                    try {
                        boolean isDirectory = Files.isDirectory(descendantPath);
                        String name = descendantPath.getFileName().toString();
                        long size = isDirectory ? -1 : Files.size(descendantPath);
                        Path relativePathObj = effectivePath.relativize(descendantPath);
                        Path parentPathObj = relativePathObj.getParent();
                        String parentRelativePath = (parentPathObj == null) ? "" : parentPathObj.toString().replace('\\', '/');
                        return new ItemInfo(parentRelativePath, name, isDirectory, size);
                    } catch (IOException e) {
                        logger.warn("Could not get attributes for path '{}', skipping item: {}", descendantPath, e.getMessage());
                        throw new UncheckedIOException("Failed to process path: " + descendantPath, e); // Use the custom exception or handle differently
                    }
                });
        } catch (IOException e) {
            logger.error("Failed to walk local directory tree '{}': {}", effectivePath, e.getMessage(), e);
            throw new IOException("Failed to walk local directory tree: " + effectivePath, e);
        }
    }

    @Override
    public InputStream openInputStream(ItemInfo item) throws IOException {
         if (isSingleFileMode) {
             logger.error("openInputStream() called but LocalDataSource is configured for single file mode.");
            throw new UnsupportedOperationException("openInputStream is not supported in single file mode for LocalDataSource");
         }

        // Existing logic for reading files found via listItems
        Objects.requireNonNull(item, "ItemInfo cannot be null");
        if (item.isDirectory()) {
            throw new IOException("Cannot open InputStream for a directory: " + item.getFullRelativePath());
        }

        Path fullPath = this.effectivePath.resolve(item.getFullRelativePath());
        logger.debug("Opening input stream for local file: {}", fullPath);

        // Re-check existence/type/readability for robustness
        if (!Files.exists(fullPath)) throw new NoSuchFileException("Local file does not exist: " + fullPath);
        if (!Files.isRegularFile(fullPath)) throw new IOException("Local path is not a regular file: " + fullPath);
        if (!Files.isReadable(fullPath)) throw new IOException("Local file is not readable: " + fullPath);

        try {
            return Files.newInputStream(fullPath);
        } catch (IOException e) {
            logger.error("Failed to open local file '{}' for reading: {}", fullPath, e.getMessage(), e);
            throw new IOException("Failed to open local file for reading: " + fullPath, e);
        }
    }

    @Override
    public InputStream openSpecificInputStream(String sourceIdentifier) throws IOException, UnsupportedOperationException {
        if (!isSingleFileMode) {
            logger.error("openSpecificInputStream() called but LocalDataSource is configured for directory mode.");
            throw new UnsupportedOperationException("openSpecificInputStream is only supported in single file mode for LocalDataSource");
        }
        // In single file mode, the sourceIdentifier *should* match the configured effectivePath
        Path requestedPath = Paths.get(sourceIdentifier).normalize();
        if (!requestedPath.equals(this.effectivePath)) {
             logger.error("Requested specific file '{}' does not match configured file path '{}'", sourceIdentifier, this.effectivePath);
             throw new IllegalArgumentException("Requested specific file does not match configuration");
        }

        logger.debug("Opening specific input stream for local file: {}", this.effectivePath);
        // Validation was already done in constructor, but double-check read is still possible
        if (!Files.isReadable(this.effectivePath)) {
            throw new IOException("Configured local source file is no longer readable: " + this.effectivePath);
        }
        try {
            return Files.newInputStream(this.effectivePath);
        } catch (IOException e) {
            logger.error("Failed to open specific local file '{}' for reading: {}", this.effectivePath, e.getMessage(), e);
            throw new IOException("Failed to open specific local file for reading: " + this.effectivePath, e);
        }
    }


    @Override
    public String getDescription() {
        return config.getDescription(); // Config class handles description based on mode
    }

    @Override
    public void close() throws IOException {
        logger.debug("Local Data Source for {} closed (no-op).", config.getDescription());
    }

    // Keep if using this approach for stream errors
    private static class UncheckedIOException extends RuntimeException {
        public UncheckedIOException(String message, IOException cause) {
            super(message, cause);
        }
    }
}