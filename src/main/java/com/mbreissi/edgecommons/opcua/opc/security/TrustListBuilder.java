package com.mbreissi.edgecommons.opcua.opc.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.TrustListManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * Builds the server-certificate validator: a {@link FileBasedTrustListManager} rooted at a per-device
 * PKI directory (creating {@code trusted}/{@code rejected}/{@code issuers}), optionally pinning a
 * configured server certificate as trusted. Rejected server certs land in the quarantine + the
 * {@code rejected} dir for operator inspection (no auto-trust).
 */
public final class TrustListBuilder {

    private static final Logger LOGGER = LogManager.getLogger(TrustListBuilder.class);

    private TrustListBuilder() {
    }

    public static DefaultClientCertificateValidator build(Path pkiDir, Optional<X509Certificate> serverAnchor) throws Exception {
        Files.createDirectories(pkiDir);
        TrustListManager trustList = FileBasedTrustListManager.createAndInitialize(pkiDir);
        serverAnchor.ifPresent(cert -> {
            try {
                trustList.addTrustedCertificate(cert);
                LOGGER.info("pinned server certificate as trusted (subject={})", cert.getSubjectX500Principal());
            } catch (Exception e) {
                LOGGER.warn("failed to pin server certificate: {}", e.toString());
            }
        });
        return new DefaultClientCertificateValidator(trustList, new MemoryCertificateQuarantine());
    }
}
