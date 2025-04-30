package org.manishsharan.cloudtransfer.sftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility methods for handling SFTP paths.
 */
public class SftpUtils {

    private static final Logger logger =  LoggerFactory.getLogger(SftpUtils.class);

    // Private constructor to prevent instantiation
    private SftpUtils() {}

    /**
     * Normalizes an SFTP path string.
     * Ensures it starts with '/', removes trailing '/' (unless it's just "/"),
     * and replaces backslashes with forward slashes.
     * Returns "/" if the input path is null, empty, or whitespace.
     *
     * @param path The raw path string.
     * @return A normalized SFTP path string (e.g., "/path/to/folder", "/path/to/file.txt", "/").
     */
    public static String normalizeSftpPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "/"; // Default to root
        }
        // Replace backslashes and trim whitespace
        String normalized = path.replace('\\', '/').trim();

        // Remove trailing slash *only* if it's not the root path itself
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // Ensure it starts with a slash
        if (!normalized.startsWith("/")) {
             // Handle case where path was just "folder" -> "/folder"
             // Or "" after trimming/removing trailing slash -> "/"
             if (normalized.isEmpty()) {
                return "/";
             }
            normalized = "/" + normalized;
        }

        // Handle case where input was just "/" -> normalized correctly
        // Handle case where input was "/folder/" -> "/folder"
        // Handle case where input was "folder/" -> "/folder"
        // Handle case where input was "" or " " -> "/"

        return normalized;
    }

    /**
     * Builds a combined remote path from a base path and a relative path component,
     * ensuring proper slash separation.
     *
     * @param basePath       The normalized base remote path (directory). Should already be normalized.
     * @param relativePath   The relative path component (file or subdirectory). Should use '/' separators.
     * Can be empty.
     * @return The combined, normalized path.
     */
    public static String buildRemotePath(String basePath, String relativePath) {
         // Assume basePath is already normalized by constructor/internal calls
        // Normalize relative path component slightly
        String cleanRelative = (relativePath == null) ? "" : relativePath.replace('\\', '/').trim();

        // Remove leading/trailing slashes from relative path to avoid double slashes or trailing slash
         while (cleanRelative.startsWith("/")) {
             cleanRelative = cleanRelative.substring(1);
         }
          while (cleanRelative.endsWith("/")) {
              cleanRelative = cleanRelative.substring(0, cleanRelative.length() - 1);
          }

        // If relative path is empty, return the base path
        if (cleanRelative.isEmpty()) {
            return basePath;
        }

        // Combine, ensuring only one slash between them
        if (basePath.equals("/")) {
            return "/" + cleanRelative;
        } else {
            // Base path already normalized (no trailing slash unless it's "/")
            return basePath + "/" + cleanRelative;
        }
    }

     /**
      * Gets the parent directory path from a normalized SFTP path.
      * Returns "/" if the path is in the root directory or is the root itself.
      * Example: getParentPath("/dir/file.txt") -> "/dir"
      * Example: getParentPath("/file.txt") -> "/"
      * Example: getParentPath("/") -> "/"
      *
      * @param normalizedPath A normalized SFTP path (file or directory). Assumed to start with '/'.
      * @return The normalized parent directory path.
      */
     public static String getParentPath(String normalizedPath) {
         if (normalizedPath == null || normalizedPath.equals("/")) {
             return "/"; // Parent of root is root
         }

         // Find the last '/'
         int lastSlash = normalizedPath.lastIndexOf('/');

         if (lastSlash == 0) {
             // Item is in the root directory (e.g., "/file.txt"), parent is "/"
             return "/";
         } else if (lastSlash > 0) {
             // Item is in a subdirectory (e.g., "/dir/file.txt" or "/dir/subdir"), parent is "/dir" or "/dir"
             return normalizedPath.substring(0, lastSlash);
         } else {
             // Should not happen for a normalized path starting with '/', but handle defensively
             logger.warn("Could not determine parent path for '{}', returning root '/'", normalizedPath);
             return "/";
         }
     }
}