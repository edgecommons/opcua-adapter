package com.mbreissi.edgecommons.opcua.opc.security;

import com.mbreissi.edgecommons.credentials.CredentialService;
import com.mbreissi.edgecommons.credentials.TlsBundle;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Client identity from the edgecommons credentials vault: reads a {@link TlsBundle} secret
 * ({@code {certPem, keyPem, caPem}}) and converts the PEM to a certificate + key pair. The
 * {@code caPem} (server trust anchor) is read separately by {@link SecurityConfig} for the trust list.
 */
public final class VaultCertSource implements CertSource {

    private final CredentialService credentials;
    private final String secretName;

    public VaultCertSource(CredentialService credentials, String secretName) {
        this.credentials = credentials;
        this.secretName = secretName;
    }

    @Override
    public ClientIdentity load() throws Exception {
        if (credentials == null) {
            throw new IllegalStateException("no credentials subsystem configured (add a 'credentials' "
                    + "config section) but the OPC UA cert source is 'vault'");
        }
        TlsBundle bundle = credentials.getTlsBundle(secretName)
                .orElseThrow(() -> new IllegalStateException(
                        "OPC UA client cert secret '" + secretName + "' not found in vault"));
        X509Certificate cert = Pem.certificate(bundle.certPem());
        PrivateKey key = Pem.privateKey(bundle.keyPem());
        return ClientIdentity.of(cert, new KeyPair(cert.getPublicKey(), key));
    }
}
