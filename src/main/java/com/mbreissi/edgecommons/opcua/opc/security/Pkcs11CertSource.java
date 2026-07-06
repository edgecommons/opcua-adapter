package com.mbreissi.edgecommons.opcua.opc.security;

import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;

/**
 * Client identity from a key + certificate resident on a PKCS#11 token (HSM/SoftHSM). The private
 * key is non-extractable; signing happens on the token. Uses the JDK's {@code SunPKCS11} provider
 * (the same mechanism the edgecommons {@code Pkcs11KeyProvider} uses for KEK wrapping).
 *
 * <p>Net-new vs the edgecommons credentials subsystem (which exposes no signing key) and untested
 * without a token — validate against SoftHSM/your HSM before production use.
 */
public final class Pkcs11CertSource implements CertSource {

    private final String modulePath;
    private final int slotIndex;
    private final String pin;
    private final String keyLabel;
    private final String certLabel;

    public Pkcs11CertSource(String modulePath, int slotIndex, String pin, String keyLabel, String certLabel) {
        this.modulePath = modulePath;
        this.slotIndex = slotIndex;
        this.pin = pin;
        this.keyLabel = keyLabel;
        this.certLabel = certLabel;
    }

    @Override
    public ClientIdentity load() throws Exception {
        String config = "--name=edgecommons-opcua\nlibrary=" + modulePath + "\nslotListIndex=" + slotIndex + "\n";
        Provider base = Security.getProvider("SunPKCS11");
        if (base == null) {
            throw new IllegalStateException("SunPKCS11 provider unavailable in this JRE");
        }
        Provider provider = base.configure(config);
        Security.addProvider(provider);

        KeyStore ks = KeyStore.getInstance("PKCS11", provider);
        ks.load(null, pin != null ? pin.toCharArray() : null);

        PrivateKey key = (PrivateKey) ks.getKey(keyLabel, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate(certLabel);
        if (key == null || cert == null) {
            throw new IllegalStateException("PKCS#11 key '" + keyLabel + "' or cert '" + certLabel + "' not found on token");
        }
        return ClientIdentity.of(cert, new KeyPair(cert.getPublicKey(), key));
    }
}
