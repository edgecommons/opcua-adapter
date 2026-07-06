package com.mbreissi.edgecommons.opcua.opc.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** Client identity from PEM files on disk ({@code certPath} + {@code keyPath}). */
public final class FileCertSource implements CertSource {

    private final Path certPath;
    private final Path keyPath;

    public FileCertSource(Path certPath, Path keyPath) {
        this.certPath = certPath;
        this.keyPath = keyPath;
    }

    @Override
    public ClientIdentity load() throws Exception {
        X509Certificate cert = Pem.certificate(Files.readString(certPath));
        PrivateKey key = Pem.privateKey(Files.readString(keyPath));
        return ClientIdentity.of(cert, new KeyPair(cert.getPublicKey(), key));
    }
}
