package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.credentials.CredentialService;
import com.mbreissi.edgecommons.opcua.opc.config.ConnectionInfo;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.opcua.opc.security.ClientIdentity;
import com.mbreissi.edgecommons.opcua.opc.security.IdentityProviders;
import com.mbreissi.edgecommons.opcua.opc.security.Pem;
import com.mbreissi.edgecommons.opcua.opc.security.SecurityConfig;
import com.mbreissi.edgecommons.opcua.opc.security.TrustListBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.UaSession;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

/**
 * Owns the OPC UA client connection for one device: creates the client for the configured security
 * policy, then connects with retry/backoff. {@code None} is anonymous; secure policies are wired by
 * the security layer (T6).
 */
public class OpcUaConnection {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaConnection.class);
    private static final long RETRY_MS = 5000;
    private static final CertificateValidator NO_SECURITY_CERTIFICATE_VALIDATOR =
            new NoSecurityCertificateValidator();
    private static final Set<Long> UNRETRYABLE_STATUS_CODES = Set.of(
            StatusCodes.Bad_ConfigurationError,
            StatusCodes.Bad_TcpEndpointUrlInvalid,
            StatusCodes.Bad_UserAccessDenied,
            StatusCodes.Bad_IdentityTokenInvalid,
            StatusCodes.Bad_IdentityTokenRejected,
            StatusCodes.Bad_IdentityChangeNotSupported,
            StatusCodes.Bad_UserSignatureInvalid,
            StatusCodes.Bad_ApplicationSignatureInvalid,
            StatusCodes.Bad_SecurityChecksFailed,
            StatusCodes.Bad_SecurityModeRejected,
            StatusCodes.Bad_SecurityPolicyRejected,
            StatusCodes.Bad_SecurityModeInsufficient,
            StatusCodes.Bad_CertificateInvalid,
            StatusCodes.Bad_CertificatePolicyCheckFailed,
            StatusCodes.Bad_CertificateTimeInvalid,
            StatusCodes.Bad_CertificateIssuerTimeInvalid,
            StatusCodes.Bad_CertificateHostNameInvalid,
            StatusCodes.Bad_CertificateUriInvalid,
            StatusCodes.Bad_CertificateUseNotAllowed,
            StatusCodes.Bad_CertificateIssuerUseNotAllowed,
            StatusCodes.Bad_CertificateUntrusted,
            StatusCodes.Bad_CertificateRevocationUnknown,
            StatusCodes.Bad_CertificateIssuerRevocationUnknown,
            StatusCodes.Bad_CertificateRevoked,
            StatusCodes.Bad_CertificateIssuerRevoked,
            StatusCodes.Bad_CertificateChainIncomplete,
            StatusCodes.Bad_NoValidCertificates);

    private final ServerConfiguration config;
    private final CredentialService credentials;
    private final ClientMetrics counters;
    private OpcUaClient client;
    private volatile boolean connected = false;

    public OpcUaConnection(ServerConfiguration config, CredentialService credentials) {
        this(config, credentials, null);
    }

    public OpcUaConnection(ServerConfiguration config, CredentialService credentials, ClientMetrics counters) {
        this.config = config;
        this.credentials = credentials;
        this.counters = counters;
    }

    public boolean isConnected() {
        return connected;
    }

    public OpcUaClient getClient() {
        return client;
    }

    /**
     * Drop and re-establish the session on the <b>same</b> {@link OpcUaClient} (Milo owns the client's
     * transport; only the session is cycled), one immediate attempt — backing the {@code reconnect}
     * command verb. The {@code SessionActivityListener} attached in {@link #connect(Runnable)} records
     * the reconnect and flips {@link #isConnected()} back to true on re-activation.
     *
     * @return {@code true} once reconnected
     * @throws Exception when the immediate reconnect attempt fails (mapped to {@code RECONNECT_FAILED})
     */
    public boolean reconnect() throws Exception {
        if (client == null) {
            throw new IllegalStateException("no OPC UA client to reconnect");
        }
        try {
            client.disconnect();
        } catch (Exception closeError) {
            LOGGER.debug("[{}] disconnect before reconnect failed: {}", config.getId(), closeError.toString());
        }
        connected = false;
        client.connect();
        connected = true;
        LOGGER.info("[{}] reconnected to {}", config.getId(), config.getConnection().getEndpoint());
        return true;
    }

    /** Blocks, retrying every {@value #RETRY_MS} ms, until connected or a terminal failure is detected. */
    public OpcUaClient connect() {
        return connect(null);
    }

    /**
     * Blocks, retrying every {@value #RETRY_MS} ms, until connected or a terminal failure is detected.
     * Invokes {@code onFailedAttempt} after each failed connection attempt, before retry or throw.
     */
    public OpcUaClient connect(Runnable onFailedAttempt) {
        ConnectionInfo connection = config.getConnection();
        String endpoint = connection.getEndpoint();
        SecurityPolicy policy = parsePolicy(connection.getSecurityPolicy());
        while (client == null) {
            OpcUaClient candidate = null;
            try {
                recordConnectionAttempt();
                candidate = createClient(endpoint, policy);
                candidate.connect();
                // Event-driven liveness (#1c): Milo fires onSessionInactive when the session drops
                // (server death / socket loss) and onSessionActive when it reconnects. This keeps
                // isConnected() live WITHOUT polling — the initial-connect flag alone never catches a
                // server that dies mid-session (which left the connectivity provider reporting a stale
                // "connected"). Attached once per client; the client owns its own reconnect loop.
                candidate.addSessionActivityListener(new SessionActivityListener() {
                    @Override
                    public void onSessionActive(UaSession session) {
                        if (client != null && !connected) {
                            recordSessionReconnect();
                        }
                        connected = true;
                    }

                    @Override
                    public void onSessionInactive(UaSession session) {
                        if (connected) {
                            recordSessionDisconnect();
                        }
                        connected = false;
                    }
                });
                client = candidate;
                connected = true;
                LOGGER.info("[{}] connected to {} (policy={})", config.getId(), endpoint, policy);
            } catch (Exception e) {
                closeFailedClient(candidate);
                client = null;
                connected = false;
                recordConnectionFailure();
                if (onFailedAttempt != null) {
                    onFailedAttempt.run();
                }
                if (isUnretryableConnectionFailure(e)) {
                    recordTerminalFailure();
                    LOGGER.error("[{}] terminal OPC UA connection failure to {}: {}",
                            config.getId(), endpoint, e.toString());
                    throw new UnretryableConnectionException(config.getId(), endpoint, e);
                }
                LOGGER.error("[{}] unable to connect to {}: {}. Retrying in {}s...",
                        config.getId(), endpoint, e.toString(), RETRY_MS / 1000);
                sleep(RETRY_MS);
            }
        }
        return client;
    }

    private void recordConnectionAttempt() {
        if (counters != null) {
            counters.recordConnectionAttempt();
        }
    }

    private void recordConnectionFailure() {
        if (counters != null) {
            counters.recordConnectionFailure();
        }
    }

    private void recordTerminalFailure() {
        if (counters != null) {
            counters.recordTerminalFailure();
        }
    }

    private void recordSessionDisconnect() {
        if (counters != null) {
            counters.recordSessionDisconnect();
        }
    }

    private void recordSessionReconnect() {
        if (counters != null) {
            counters.recordSessionReconnect();
        }
    }

    private void closeFailedClient(OpcUaClient failedClient) {
        if (failedClient == null) {
            return;
        }
        try {
            failedClient.disconnect();
        } catch (Exception closeError) {
            LOGGER.debug("[{}] failed to close rejected OPC UA client attempt: {}",
                    config.getId(), closeError.toString());
        }
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
                            .setApplicationName(LocalizedText.english("EdgeCommons OPC UA Adapter"))
                            .setApplicationUri("urn:edgecommons:opcua:adapter")
                            .setCertificateValidator(NO_SECURITY_CERTIFICATE_VALIDATOR)
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
                        .setApplicationName(LocalizedText.english("EdgeCommons OPC UA Adapter"))
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

    static boolean isUnretryableConnectionFailure(Throwable error) {
        return UaException.extractStatusCode(error)
                .map(OpcUaConnection::isUnretryableStatus)
                .orElse(false);
    }

    private static boolean isUnretryableStatus(StatusCode status) {
        return status.isSecurityError() || UNRETRYABLE_STATUS_CODES.contains(status.getValue());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * SecurityPolicy.None provides no server-authentication guarantee. Milo's default validator for
     * that mode is intentionally noisy; use an explicit silent no-op so retry loops don't flood the
     * console while preserving the no-security semantics.
     */
    private static final class NoSecurityCertificateValidator implements CertificateValidator {
        @Override
        public void validateCertificateChain(List<X509Certificate> certificateChain,
                                             String applicationUri,
                                             String[] validHostNames) throws UaException {
            // Deliberately no-op for SecurityPolicy.None only.
        }
    }

    /** A connection failure that cannot be repaired by backoff, such as bad credentials or trust config. */
    public static final class UnretryableConnectionException extends RuntimeException {
        UnretryableConnectionException(String instanceId, String endpoint, Throwable cause) {
            super("unretryable OPC UA connection failure for " + instanceId + " at " + endpoint
                    + ": " + cause.toString(), cause);
        }
    }
}
