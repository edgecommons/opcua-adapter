package com.mbreissi.opcua.opcuaadapter.opc.security;

import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.credentials.CredentialService;
import com.mbreissi.ggcommons.credentials.TlsBundle;
import com.mbreissi.opcua.opcuaadapter.opc.config.ConnectionInfo;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * Parses the per-instance {@code connection} security block into the pieces the connection layer
 * needs: policy, message mode, optional application URI, the client {@link CertSource}, the server
 * trust anchor, and the PKI directory. See docs/SOUTHBOUND.md / the adapter README for the schema.
 */
public class SecurityConfig {

    private static final Logger LOGGER = LogManager.getLogger(SecurityConfig.class);

    private final SecurityPolicy policy;
    private final MessageSecurityMode messageMode;
    private final String applicationUri; // nullable -> derive from cert SAN URI
    private final CertSource certSource;
    private final X509Certificate serverTrustAnchor; // nullable
    private final Path pkiDir;

    private SecurityConfig(SecurityPolicy policy, MessageSecurityMode messageMode, String applicationUri,
                           CertSource certSource, X509Certificate serverTrustAnchor, Path pkiDir) {
        this.policy = policy;
        this.messageMode = messageMode;
        this.applicationUri = applicationUri;
        this.certSource = certSource;
        this.serverTrustAnchor = serverTrustAnchor;
        this.pkiDir = pkiDir;
    }

    public SecurityPolicy policy() {
        return policy;
    }

    public MessageSecurityMode messageMode() {
        return messageMode;
    }

    public String applicationUri() {
        return applicationUri;
    }

    public CertSource certSource() {
        return certSource;
    }

    public Optional<X509Certificate> serverTrustAnchor() {
        return Optional.ofNullable(serverTrustAnchor);
    }

    public Path pkiDir() {
        return pkiDir;
    }

    public static SecurityConfig from(ServerConfiguration config, CredentialService credentials) throws Exception {
        ConnectionInfo conn = config.getConnection();
        JsonObject raw = conn.raw();

        SecurityPolicy policy = SecurityPolicy.valueOf(conn.getSecurityPolicy());
        MessageSecurityMode mode = parseMode(conn.getMessageMode());
        if (policy != SecurityPolicy.None && mode == MessageSecurityMode.None) {
            LOGGER.warn("[{}] secure policy {} with messageMode=None; defaulting to SignAndEncrypt",
                    config.getId(), policy);
            mode = MessageSecurityMode.SignAndEncrypt;
        }
        String appUri = raw.has("applicationUri") ? raw.get("applicationUri").getAsString() : null;

        CertSource certSource = buildCertSource(config, credentials,
                raw.has("clientCertificate") ? raw.getAsJsonObject("clientCertificate") : new JsonObject());

        JsonObject trust = raw.has("trust") ? raw.getAsJsonObject("trust") : new JsonObject();
        Path pkiDir = Path.of(config.resolveTemplate(
                trust.has("pkiDir") ? trust.get("pkiDir").getAsString() : "pki/{InstanceId}"));
        X509Certificate anchor = resolveServerAnchor(config, credentials, trust);

        return new SecurityConfig(policy, mode, appUri, certSource, anchor, pkiDir);
    }

    private static CertSource buildCertSource(ServerConfiguration config, CredentialService creds, JsonObject cc) {
        String source = cc.has("source") ? cc.get("source").getAsString() : "vault";
        switch (source) {
            case "vault":
                return new VaultCertSource(creds, cc.get("secret").getAsString());
            case "file":
                return new FileCertSource(
                        Path.of(config.resolveTemplate(cc.get("certPath").getAsString())),
                        Path.of(config.resolveTemplate(cc.get("keyPath").getAsString())));
            case "pkcs11":
                return new Pkcs11CertSource(
                        cc.get("modulePath").getAsString(),
                        cc.has("slotIndex") ? cc.get("slotIndex").getAsInt() : 0,
                        resolvePin(cc),
                        cc.get("keyLabel").getAsString(),
                        cc.get("certLabel").getAsString());
            default:
                throw new IllegalArgumentException("unknown clientCertificate.source: " + source);
        }
    }

    private static X509Certificate resolveServerAnchor(ServerConfiguration config, CredentialService creds, JsonObject trust) throws Exception {
        if (!trust.has("serverCertificate")) {
            return null;
        }
        JsonObject sc = trust.getAsJsonObject("serverCertificate");
        String source = sc.has("source") ? sc.get("source").getAsString() : "vault";
        if ("vault".equals(source)) {
            if (creds == null) {
                return null;
            }
            TlsBundle bundle = creds.getTlsBundle(sc.get("secret").getAsString()).orElse(null);
            if (bundle != null && bundle.caPem() != null) {
                return Pem.certificate(bundle.caPem());
            }
            return null;
        }
        if ("file".equals(source)) {
            return Pem.certificate(Files.readString(Path.of(config.resolveTemplate(sc.get("path").getAsString()))));
        }
        return null;
    }

    private static String resolvePin(JsonObject cc) {
        if (cc.has("pin")) {
            return cc.get("pin").getAsString();
        }
        if (cc.has("pinEnv")) {
            return System.getenv(cc.get("pinEnv").getAsString());
        }
        return null;
    }

    private static MessageSecurityMode parseMode(String name) {
        try {
            return MessageSecurityMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return MessageSecurityMode.None;
        }
    }
}
