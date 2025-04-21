package org.manishsharan.cloudtransfer.gcp;

/**
 * Utility methods for Google Cloud Storage interactions.
 */
public class GcpUtils {

    // Private constructor to prevent instantiation
    private GcpUtils() {}

    /**
     * Normalizes a GCS folder prefix.
     * Ensures it ends with "/" if it's not null or empty.
     * Returns an empty string if the input is null or empty (represents bucket root).
     */
    public static String normalizeFolderPrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return "";
        }
        String trimmedPrefix = prefix.trim();
        // Ensure consistency: remove leading slash if present, add trailing if missing
        while (trimmedPrefix.startsWith("/")) {
             trimmedPrefix = trimmedPrefix.substring(1);
        }
        if (!trimmedPrefix.isEmpty() && !trimmedPrefix.endsWith("/")) {
            return trimmedPrefix + "/";
        }
        // Handle case where prefix was just "/" -> becomes "" -> return ""
        if (trimmedPrefix.isEmpty()) return "";

        return trimmedPrefix;
    }

     /**
      * Extracts the simple name (last part) from a GCS object name, handling trailing slashes for directories.
      */
     public static String simpleNameFromBlobName(String blobName) {
         if (blobName == null || blobName.isEmpty()) return "";
         String name = blobName.trim(); // Trim whitespace
         // If it represents a directory, remove trailing slash to get the directory name itself
         if (name.endsWith("/")) {
             name = name.substring(0, name.length() - 1);
             // Handle case where name was just "/" after trimming
             if (name.isEmpty()) return "";
         }
         int lastSlash = name.lastIndexOf('/');
         return (lastSlash >= 0) ? name.substring(lastSlash + 1) : name;
     }

      /**
      * **Corrected:** Extracts the parent path component relative to a given prefix.
      *
      * @param prefix The normalized source folder prefix (e.g., "source/folder/"). Must end with '/' if not empty.
      * @param blobName The full name of the blob (e.g., "source/folder/sub/file.txt").
      * @return The relative parent path (e.g., "sub") or "" if the blob is directly under the prefix.
      */
     public static String parentRelativePathFromBlobName(String prefix, String blobName) {
        // Basic validation and normalization (though prefix should be normalized already)
        String normPrefix = normalizeFolderPrefix(prefix);
        String normBlobName = (blobName == null) ? "" : blobName.trim();

        // Ensure blobName actually starts with the prefix (after normalization)
        if (normPrefix.isEmpty() || !normBlobName.startsWith(normPrefix)) {
            // If no prefix, or blob isn't under prefix, handle relative to root
             int lastSlash = normBlobName.lastIndexOf('/');
             if (normBlobName.endsWith("/")) { // Handle directory markers like "a/b/" -> parent is "a"
                 lastSlash = normBlobName.substring(0, normBlobName.length() - 1).lastIndexOf('/');
             }
             return (lastSlash < 0) ? "" : normBlobName.substring(0, lastSlash);
        }

        // Get the part of the blob name *after* the prefix
        String nameRelativeToPrefix = normBlobName.substring(normPrefix.length());
        if (nameRelativeToPrefix.isEmpty()) {
             return ""; // Blob name is identical to prefix
        }

        // Trim trailing slash if it represents a directory marker itself
        if (nameRelativeToPrefix.endsWith("/")) {
             nameRelativeToPrefix = nameRelativeToPrefix.substring(0, nameRelativeToPrefix.length() - 1);
             if (nameRelativeToPrefix.isEmpty()) {
                 return ""; // Was just the trailing slash after prefix (e.g., prefix="a/", blob="a/")
             }
        }

        // Find the last slash *within the part relative to the prefix*
        int lastSlash = nameRelativeToPrefix.lastIndexOf('/');
        if (lastSlash < 0) {
            return ""; // No parent component relative to prefix (e.g., prefix="a/", blob="a/file.txt")
        }

        // Return the substring up to the last slash
        // e.g., if nameRelativeToPrefix is "b/c/file.txt", lastSlash is at index 3, return "b/c"
        return nameRelativeToPrefix.substring(0, lastSlash);
    }
}