package com.mbreissi.opcua.opcuaadapter.opc;

import com.mbreissi.ggcommons.credentials.CredentialService;
import com.mbreissi.opcua.opcuaadapter.opc.config.ConnectionInfo;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.google.gson.JsonObject;
import com.mbreissi.opcua.opcuaadapter.opc.security.ClientIdentity;
import com.mbreissi.opcua.opcuaadapter.opc.security.IdentityProviders;
import com.mbreissi.opcua.opcuaadapter.opc.security.Pem;
import com.mbreissi.opcua.opcuaadapter.opc.security.SecurityConfig;
import com.mbreissi.opcua.opcuaadapter.opc.security.TrustListBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Owns the OPC UA client connection for one device: creates the client for the configured security
 * policy, then connects with retry/backoff. {@code None} is anonymous; secure policies are wired by
 * the security layer (T6).
 */
public class OpcUaConnection {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaConnection.class);
    private static final long RETRY_MS = 5000;
    /** Timeout for the active liveness probe read — a dead server must not hang the device tick. */
    private static final long PROBE_TIMEOUT_MS = 3000;

    private final ServerConfiguration config;
    private final CredentialService credentials;
    private OpcUaClient client;
    private volatile boolean connected = false;

    public OpcUaConnection(ServerConfiguration config, CredentialService credentials) {
        this.config = config;
        this.credentials = credentials;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Active liveness probe: reads a cheap, always-present server node ({@code Server ServerStatus
     * State}). A good read → {@code connected = true}; any failure (dead session/socket, or the
     * timeout) → {@code connected = false}. Called from the device tick so {@link #isConnected()}
     * reflects the LIVE state — the initial-connect flag alone never catches a server that dies
     * mid-session, which would leave the #1c connectivity provider reporting a stale "connected".
     */
    public void probe() {
        OpcUaClient c = client;
        if (c == null) {
            connected = false;
            return;
        }
        try {
            List<DataValue> values = c.readValuesAsync(0.0, TimestampsToReturn.Neither,
                    List.of(NodeIds.Server_ServerStatus_State)).get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            DataValue v = values.isEmpty() ? null : values.get(0);
            connected = v != null && v.getStatusCode() != null && v.getStatusCode().isGood();
        } catch (Exception e) {
            connected = false;
        }
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

    private OpcUaClient createClient(String endpoint, SecurityPolicy policy) throws Exception {
        JsonObject user = config.getConnection().getUser();
        if (policy == SecurityPolicy.None) {
            // No channel security, but the server may still require a UserName token (e.g. KEPServerEX
            // by default) -- identity comes from the optional connection.user block. Lambda param types
            // are inferred from the create() overload, sidestepping the package-moved builder types.
            LOGGER.info("[{}] identity: {} (policy=None)", config.getId(), IdentityProviders.describe(user));
            return OpcUaClient.create(
                    endpoint,
                    endpoints -> endpoints.stream()
                            .filter(e -> e.getSecurityPolicyUri().equals(SecurityPolicy.None.getUri()))
                            .findFirst(),
                    transport -> { },
                    cfg -> cfg
                            .setApplicationName(LocalizedText.english("GGCommons OPC UA Adapter"))
                            .setApplicationUri("urn:ggcommons:opcua:adapter")
                            .setIdentityProvider(IdentityProviders.from(user, credentials)));
        }
        // Secure channel (e.g. Basic256Sha256 / SignAndEncrypt): client cert/key from the configured
        // source (vault/file/pkcs11), server trust via the per-device PKI dir + optional pinned cert.
        SecurityConfig sec = SecurityConfig.from(config, credentials);
        ClientIdentity identity = sec.certSource().load();
        String appUri = sec.applicationUri() != null
                ? sec.applicationUri()
                : Pem.sanUri(identity.certificate()).orElseThrow(() -> new UaException(
                        StatusCodes.Bad_ConfigurationError,
                        "client certificate has no SubjectAltName URI; set connection.applicationUri"));
        DefaultClientCertificateValidator validator = TrustListBuilder.build(sec.pkiDir(), sec.serverTrustAnchor());
        MessageSecurityMode mode = sec.messageMode();
        LOGGER.info("[{}] secure connect: policy={} mode={} appUri={} identity={}",
                config.getId(), policy, mode, appUri, IdentityProviders.describe(user));
        return OpcUaClient.create(
                endpoint,
                endpoints -> endpoints.stream()
                        .filter(e -> e.getSecurityPolicyUri().equals(policy.getUri()) && e.getSecurityMode() == mode)
                        .findFirst(),
                transport -> { },
                cfg -> cfg
                        .setApplicationName(LocalizedText.english("GGCommons OPC UA Adapter"))
                        .setApplicationUri(appUri)
                        .setKeyPair(identity.keyPair())
                        .setCertificate(identity.certificate())
                        .setCertificateChain(identity.chain())
                        .setCertificateValidator(validator)
                        .setIdentityProvider(IdentityProviders.from(user, credentials)));
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
