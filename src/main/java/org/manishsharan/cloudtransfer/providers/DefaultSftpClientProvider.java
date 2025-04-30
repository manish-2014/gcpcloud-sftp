package org.manishsharan.cloudtransfer.providers;
import net.schmizz.sshj.SSHClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import org.manishsharan.cloudtransfer.config.SftpConfig;

import java.io.IOException;

/**
 * Default implementation of SftpClientProvider.
 *
 * NOTE: This default implementation does NOT provide a functional client.
 * There is no universal default authentication method for SFTP (unlike ADC for GCP).
 * Users of the library MUST provide their own implementation of SftpClientProvider
 * that handles their specific authentication mechanism (e.g., loading a key from a
 * specific path, fetching credentials from a secrets manager, using password auth).
 */
public class DefaultSftpClientProvider implements SftpClientProvider {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSftpClientProvider.class);

    private static DefaultSftpClientProvider instance;

    // Private constructor for potential singleton pattern
    private DefaultSftpClientProvider() {}

    public static synchronized DefaultSftpClientProvider getInstance() {
        if (instance == null) {
            instance = new DefaultSftpClientProvider();
        }
        return instance;
    }

    @Override
    public SSHClient getSshClient(SftpConfig config) throws IOException {
        String errorMessage = "DefaultSftpClientProvider cannot create an authenticated client. " +
                              "Please provide a custom SftpClientProvider implementation that handles " +
                              "your specific SFTP authentication method (e.g., key loading, secrets manager).";
        logger.error(errorMessage);
        // Throwing an exception makes it clear this default isn't usable directly.
        throw new UnsupportedOperationException(errorMessage);

        // --- Example of what a custom implementation might do (e.g., reading key from specific path) ---
        /*
        SSHClient sshClient = new SSHClient();
        try {
            // IMPORTANT: Add proper host key verification here!
            sshClient.addHostKeyVerifier(new PromiscuousVerifier()); // Replace in real implementation

            sshClient.connect(config.getSftpServer(), config.getSftpPort());

            // --- Custom logic to get key material ---
            // Option 1: Read from a configured path (requires adding config back or passing path differently)
            // String keyPath = "/path/configured/elsewhere/.ssh/id_ed25519";
            // KeyProvider keyProvider = sshClient.loadKeys(keyPath);

            // Option 2: Get key material (String or byte[]) from Vault/Secrets Manager
            // String privateKeyMaterial = fetchPrivateKeyFromVault();
            // String publicKeyMaterial = fetchPublicKeyFromVault(); // Optional, often inferred
            // KeyProvider keyProvider = sshClient.loadKeys(privateKeyMaterial, publicKeyMaterial, null); // null for passphrase

            // Option 3: Password authentication (if implemented)
            // String password = fetchPasswordFromVault();
            // sshClient.authPassword(config.getSshUser(), password);
            // --- End custom logic ---

            // Example using key provider from Option 1/2:
            // sshClient.authPublickey(config.getSshUser(), keyProvider);

            logger.info("SFTP client connected and authenticated (in custom provider).");
            return sshClient;

        } catch (IOException e) {
            logger.error("Failed to connect/authenticate SFTP client in custom provider: {}", e.getMessage(), e);
            if (sshClient != null && sshClient.isConnected()) {
                try { sshClient.disconnect(); } catch (IOException nested) { 
             //Log nested  
             }
            }
            throw e;
        }
        */
    }
}

