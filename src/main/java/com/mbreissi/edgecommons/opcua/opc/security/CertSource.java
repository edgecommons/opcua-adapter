package com.mbreissi.edgecommons.opcua.opc.security;

/**
 * A source of the OPC UA client's application instance certificate + key pair. Implementations:
 * {@link VaultCertSource} (edgecommons credentials vault), {@link FileCertSource} (PEM files on disk),
 * {@link Pkcs11CertSource} (a key/cert resident on a PKCS#11 token).
 */
public sealed interface CertSource permits VaultCertSource, FileCertSource, Pkcs11CertSource {

    ClientIdentity load() throws Exception;
}
