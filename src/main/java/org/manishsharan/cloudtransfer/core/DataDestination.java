package org.manishsharan.cloudtransfer.core;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Represents a destination location to which data can be written.
 * Implementations must be AutoCloseable to manage resources like connections.
 */
public interface DataDestination extends AutoCloseable {

    /**
     * Opens an OutputStream to write content to a specific file item discovered from the source.
     * Required for directory/prefix transfer mode.
     * The path for the item is relative to the configured base path.
     * The caller is responsible for closing the returned OutputStream.
     *
     * @param item The ItemInfo representing the file (must have isDirectory=false).
     * The relativePath field determines where the file will be written.
     * @return An OutputStream to write the item's content.
     * @throws IOException If the destination path cannot be written to or directories cannot be created.
     */
    OutputStream openOutputStream(ItemInfo item) throws IOException;

    /**
     * Opens an OutputStream to write content to a specific file name within the
     * configured destination base path/prefix.
     * Required for single-file transfer mode.
     * The caller is responsible for closing the returned OutputStream. Implementations
     * must ensure necessary parent directories are created.
     *
     * @param targetName The simple name (or name relative to base path) for the destination file.
     * @return An OutputStream to write the item's content.
     * @throws IOException If the destination path cannot be written to or directories cannot be created.
     * @throws UnsupportedOperationException If the destination requires a specific file path config
     * and only a directory was provided (optional behavior).
     */
    OutputStream openSpecificOutputStream(String targetName) throws IOException, UnsupportedOperationException;


    /**
     * Ensures that the directory structure required for the given item exists.
     * Called for both files (to ensure parent dir exists) and directories during
     * directory/prefix transfer mode.
     *
     * @param item The ItemInfo representing the file or directory.
     * @throws IOException If directory creation fails.
     */
    void ensureDirectoryExists(ItemInfo item) throws IOException;

    /**
     * Gets the configuration description for logging.
     * @return A string describing the destination.
     */
    String getDescription();

    /**
     * Closes any underlying resources (e.g., network connections).
     * Overrides AutoCloseable.close().
     * @throws IOException If closing fails.
     */
    @Override
    void close() throws IOException;
}