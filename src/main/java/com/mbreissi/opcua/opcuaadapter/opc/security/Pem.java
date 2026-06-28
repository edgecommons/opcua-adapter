package com.mbreissi.opcua.opcuaadapter.opc.security;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;

import java.io.StringReader;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PEM ↔ Java crypto helpers (BouncyCastle): parse an X.509 certificate and a private key (PKCS#1 or
 * PKCS#8, RSA or EC) from PEM text, and extract a certificate's SubjectAltName URI.
 */
public final class Pem {

    private static final int GENERAL_NAME_URI = 6; // GeneralName.uniformResourceIdentifier

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private Pem() {
    }

    public static X509Certificate certificate(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (!(obj instanceof X509CertificateHolder holder)) {
                throw new IllegalArgumentException("not an X.509 certificate PEM: "
                        + (obj == null ? "null" : obj.getClass().getName()));
            }
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        }
    }

    public static PrivateKey privateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair).getPrivate();   // PKCS#1
            }
            if (obj instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);             // PKCS#8
            }
            throw new IllegalArgumentException("unsupported private key PEM: "
                    + (obj == null ? "null" : obj.getClass().getName()));
        }
    }

    /**
     * The certificate's SubjectAltName URI, if present. OPC UA requires the client's
     * {@code applicationUri} to byte-for-byte equal this value, or the server rejects the session.
     */
    public static Optional<String> sanUri(X509Certificate cert) {
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return Optional.empty();
            }
            for (List<?> san : sans) {
                if (san.size() >= 2 && ((Number) san.get(0)).intValue() == GENERAL_NAME_URI) {
                    return Optional.ofNullable((String) san.get(1));
                }
            }
        } catch (CertificateParsingException e) {
            // fall through
        }
        return Optional.empty();
    }
}
