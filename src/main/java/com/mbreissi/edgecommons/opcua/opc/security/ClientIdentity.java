package com.mbreissi.edgecommons.opcua.opc.security;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The OPC UA client's application instance identity: its certificate (+ chain) and key pair, as
 * required by Milo's {@code setCertificate} / {@code setKeyPair} / {@code setCertificateChain}.
 */
public record ClientIdentity(X509Certificate certificate, X509Certificate[] chain, KeyPair keyPair) {

    /** A self-contained identity: the leaf is the whole chain (a directly-trusted client cert). */
    public static ClientIdentity of(X509Certificate certificate, KeyPair keyPair) {
        return new ClientIdentity(certificate, new X509Certificate[]{certificate}, keyPair);
    }

    /**
     * An identity whose leaf was issued by one or more intermediate CAs. The leaf is the first entry;
     * the remainder are presented to the server so it can build a path to a trusted root.
     */
    public static ClientIdentity of(List<X509Certificate> chain, KeyPair keyPair) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("a client identity needs at least one certificate");
        }
        return new ClientIdentity(chain.get(0), chain.toArray(new X509Certificate[0]), keyPair);
    }
}
