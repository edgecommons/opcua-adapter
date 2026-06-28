package com.mbreissi.opcua.opcuaadapter.opc;

import com.mbreissi.opcua.opcuaadapter.opc.config.ConnectionInfo;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;

/**
 * Owns the OPC UA client connection for one device: creates the client for the configured security
 * policy, then connects with retry/backoff. {@code None} is anonymous; secure policies are wired by
 * the security layer (T6).
 */
public class OpcUaConnection {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaConnection.class);
    private static final long RETRY_MS = 5000;

    private final ServerConfiguration config;
    private OpcUaClient client;
    private volatile boolean connected = false;

    public OpcUaConnection(ServerConfiguration config) {
        this.config = config;
    }

    public boolean isConnected() {
        return connected;
    }

    public OpcUaClient getClient() {
        return client;
    }

    /** Blocks, retrying every {@value #RETRY_MS} ms, until the client is created and connected. */
    public OpcUaClient connect() {
        ConnectionInfo connection = config.getConnection();
        String endpoint = connection.getEndpoint();
        SecurityPolicy policy = parsePolicy(connection.getSecurityPolicy());
        while (client == null) {
            try {
                client = createClient(endpoint, policy);
                client.connect();
                connected = true;
                LOGGER.info("[{}] connected to {} (policy={})", config.getId(), endpoint, policy);
            } catch (Exception e) {
                client = null;
                connected = false;
                LOGGER.error("[{}] unable to connect to {}: {}. Retrying in {}s...",
                        config.getId(), endpoint, e.toString(), RETRY_MS / 1000);
                sleep(RETRY_MS);
            }
        }
        return client;
    }

    private OpcUaClient createClient(String endpoint, SecurityPolicy policy) throws UaException {
        if (policy == SecurityPolicy.None) {
            // Anonymous, no security. Lambda param types are inferred from the create() overload,
            // which sidesteps importing the (package-moved) config/transport builder types.
            return OpcUaClient.create(
                    endpoint,
                    endpoints -> endpoints.stream()
                            .filter(e -> e.getSecurityPolicyUri().equals(SecurityPolicy.None.getUri()))
                            .findFirst(),
                    transport -> { },
                    cfg -> cfg
                            .setApplicationName(LocalizedText.english("GGCommons OPC UA Adapter"))
                            .setApplicationUri("urn:ggcommons:opcua:adapter")
                            .setIdentityProvider(new AnonymousProvider()));
        }
        // TODO(security, T6): secure channel (Basic256Sha256/SignAndEncrypt) sourced from the
        // credentials vault. Until implemented, fail fast rather than silently downgrade.
        throw new UaException(StatusCodes.Bad_NotImplemented,
                "Secure OPC UA connections not yet implemented (policy=" + policy + ")");
    }

    private SecurityPolicy parsePolicy(String name) {
        try {
            return SecurityPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[{}] invalid securityPolicy '{}', defaulting to None", config.getId(), name);
            return SecurityPolicy.None;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
