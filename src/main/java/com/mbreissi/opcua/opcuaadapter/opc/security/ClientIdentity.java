package com.mbreissi.opcua.opcuaadapter.opc.security;

import java.security.KeyPair;
import java.security.cert.X509Certificate;

/**
 * The OPC UA client's application instance identity: its certificate (+ chain) and key pair, as
 * required by Milo's {@code setCertificate} / {@code setKeyPair} / {@code setCertificateChain}.
 */
public record ClientIdentity(X509Certificate certificate, X509Certificate[] chain, KeyPair keyPair) {

    public static ClientIdentity of(X509Certificate certificate, KeyPair keyPair) {
        return new ClientIdentity(certificate, new X509Certificate[]{certificate}, keyPair);
    }
}
