package org.manishsharan.cloudtransfer.providers;


import net.schmizz.sshj.SSHClient;
import org.manishsharan.cloudtransfer.config.SftpConfig;

import java.io.IOException;

/**
 * Interface for providing connected and authenticated SSHClient instances for SFTP operations.
 * Implementations handle the specific mechanism for authentication (e.g., key files,
 * key material from secrets manager, password - if implemented).
 */
@FunctionalInterface // Good practice for single-method interfaces
public interface SftpClientProvider {

    /**
     * Creates, connects, and authenticates an SSHClient based on the provided configuration.
     *
     * The implementation is responsible for:
     * 1. Creating an SSHClient instance.
     * 2. Adding necessary host key verification (IMPORTANT: Avoid PromiscuousVerifier in production).
     * 3. Connecting to the server specified in the config.
     * 4. Authenticating using credentials (e.g., username from config + key material/password obtained securely).
     *
     * @param config The SFTP configuration containing server address, port, user, etc.
     * (Note: It no longer contains the sshKeyPath).
     * @return A fully connected and authenticated SSHClient instance.
     * @throws IOException If connection or authentication fails.
     */
    SSHClient getSshClient(SftpConfig config) throws IOException;

    // We could add a close() method if the provider itself holds resources,
    // but typically the client returned is managed/closed by the DataSource/Destination.
}
