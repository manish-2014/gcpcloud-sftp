package org.manishsharan.cloudtransfer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.Valid; // Using Jakarta Validation API
import jakarta.validation.constraints.NotNull;

/**
 * Main configuration for the transfer operation, defining source and destination.
 */
@JsonIgnoreProperties(ignoreUnknown = true) // Be lenient with extra fields
public class TransferConfig {

    @JsonProperty(required = true)
    @NotNull // Use validation API
    @Valid   // Validate nested object
    private LocationConfig source;

    @JsonProperty(required = true)
    @NotNull
    @Valid
    private LocationConfig destination;

    // --- Getters and Setters ---
    public LocationConfig getSource() { return source; }
    public void setSource(LocationConfig source) { this.source = source; }
    public LocationConfig getDestination() { return destination; }
    public void setDestination(LocationConfig destination) { this.destination = destination; }

    @Override
    public String toString() {
        return "TransferConfig{" +
               "source=" + source + // Rely on LocationConfig's toString
               ", destination=" + destination +
               '}';
    }
}