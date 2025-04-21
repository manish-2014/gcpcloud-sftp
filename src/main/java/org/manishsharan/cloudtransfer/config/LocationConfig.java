package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank; // Use validation API

/**
 * Abstract base class for location configurations (Source or Destination).
 * Uses Jackson annotations for polymorphic deserialization based on the "type" field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type", // The field in JSON that determines the subtype
        visible = true // Make the 'type' property itself available if needed
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = LocalConfig.class, name = "LOCAL"),
        @JsonSubTypes.Type(value = SftpConfig.class, name = "SFTP"),
        @JsonSubTypes.Type(value = GcpConfig.class, name = "GCP_BUCKET")
        // Add new types here, e.g. @JsonSubTypes.Type(value = S3Config.class, name = "S3")
})
public abstract class LocationConfig {

    @NotBlank(message = "Location 'type' must be provided (e.g., LOCAL, SFTP, GCP_BUCKET)")
    protected String type; // The type field itself

    // Abstract method to force subclasses to provide a description for logging
    public abstract String getDescription();

    // --- Getters ---
    public String getType() {
        return type;
    }

    // Setter for type is implicitly handled by Jackson during deserialization

    @Override
    public String toString() {
        // Provide a basic representation, subclasses should override for more detail
        return "type='" + type + '\'';
    }
}