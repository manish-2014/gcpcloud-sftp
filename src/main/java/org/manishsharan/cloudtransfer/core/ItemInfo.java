package org.manishsharan.cloudtransfer.core;

import java.util.Objects;

/**
 * Represents an item (file or directory) being transferred.
 * Paths are relative to the base path configured for the source/destination.
 */
public class ItemInfo {
    private final String relativePath; // Path relative to the source/destination base path, using '/'
    private final String name; // Simple name of the file or directory
    private final boolean isDirectory;
    private final long size; // Size in bytes, -1 if unknown or not applicable (directory)

    public ItemInfo(String relativePath, String name, boolean isDirectory, long size) {
        // Basic validation/normalization
        this.relativePath = normalizeRelativePath(relativePath);
        this.name = Objects.requireNonNull(name, "Item name cannot be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Item name cannot be empty");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Item name cannot contain path separators: " + name);
        }
        this.isDirectory = isDirectory;
        this.size = size;
    }

    // Helper to ensure consistent relative path format
    private String normalizeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.equals(".")) {
            return ""; // Represents the base directory itself (usually skipped in iteration)
        }
        String normalized = path.replace('\\', '/');
        // Remove leading/trailing slashes for consistency within relative paths
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // Getters
    public String getRelativePath() {
        return relativePath;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public long getSize() {
        return size;
    } // Returns -1 for directories or unknown size

    /**
     * Gets the full relative path including the name.
     * Example: if relativePath="dir" and name="file.txt", returns "dir/file.txt".
     * If relativePath="", returns name.
     */
    public String getFullRelativePath() {
        if (relativePath.isEmpty()) {
            return name;
        } else {
            return relativePath + "/" + name;
        }
    }

    /**
     * Gets the parent directory's relative path.
     * Returns "" if the item is in the base directory.
     */
    public String getParentRelativePathOld() {
        if (relativePath.isEmpty()) {
            return ""; // Item is in the root
        }
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return ""; // Item is directly in root, parent is root
        } else {
            return relativePath.substring(0, lastSlash);
        }
    }

    /**
     * Gets the parent directory's relative path (stored during construction).
     * Returns "" if the item is in the base directory (i.e., parent path was
     * originally empty).
     */
    public String getParentRelativePath() {
        // Simply return the stored relative path, which represents the parent's path
        return this.relativePath;
    }

    @Override
    public String toString() {
        return "ItemInfo{" +
                "relativePath='" + relativePath + '\'' +
                ", name='" + name + '\'' +
                ", isDirectory=" + isDirectory +
                ", size=" + size +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ItemInfo itemInfo = (ItemInfo) o;
        return isDirectory == itemInfo.isDirectory &&
                size == itemInfo.size &&
                Objects.equals(relativePath, itemInfo.relativePath) &&
                Objects.equals(name, itemInfo.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, name, isDirectory, size);
    }
}