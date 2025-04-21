package org.manishsharan.cloudtransfer.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

/**
 * Represents a source location from which data can be read.
 * Implementations must be AutoCloseable to manage resources like connections.
 */
public interface DataSource extends AutoCloseable {

    /**
     * Lists items (files and directories) recursively under the configured base path/prefix.
     * Required for directory/prefix transfer mode.
     * The paths in ItemInfo should be relative to the base path.
     *
     * @return A Stream of ItemInfo objects for all descendants.
     * @throws IOException If listing fails.
     * @throws UnsupportedOperationException If the source is configured for single-file mode.
     */
    Stream<ItemInfo> listItems() throws IOException, UnsupportedOperationException;

    /**
     * Opens an InputStream to read the content of a specific file item discovered during listing.
     * Required for directory/prefix transfer mode.
     * The caller is responsible for closing the returned InputStream.
     *
     * @param item The ItemInfo representing the file (must have isDirectory=false).
     * @return An InputStream for the item's content.
     * @throws IOException If the item cannot be found, is not a file, or reading fails.
     */
    InputStream openInputStream(ItemInfo item) throws IOException;

    /**
     * Opens an InputStream to read the content of a specific file defined directly
     * by its full path/name within the source configuration.
     * Required for single-file transfer mode.
     * The caller is responsible for closing the returned InputStream.
     *
     * @param sourceIdentifier The specific path or object name of the single file to read
     * (e.g., "/path/to/file.txt", "prefix/file.obj").
     * @return An InputStream for the file's content.
     * @throws IOException If the file cannot be found or read.
     * @throws UnsupportedOperationException If the source is configured for directory/prefix mode.
     */
    InputStream openSpecificInputStream(String sourceIdentifier) throws IOException, UnsupportedOperationException;


    /**
     * Gets the configuration description for logging.
     * @return A string describing the source.
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