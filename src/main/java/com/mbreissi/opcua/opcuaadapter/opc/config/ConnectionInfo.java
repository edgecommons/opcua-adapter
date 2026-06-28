package com.mbreissi.opcua.opcuaadapter.opc.config;

import com.google.gson.JsonObject;

/**
 * Per-instance OPC UA connection config (southbound convention):
 * {@code "connection": { "endpoint": "opc.tcp://host:4840/", "securityPolicy": "None|Basic256Sha256",
 * "messageMode": "None|Sign|SignAndEncrypt", ... }}.
 *
 * <p>The certificate-source fields used for secure connections (vault / file / pkcs11) are parsed by
 * the security layer; this holds the policy selection and endpoint. Defaults are {@code None}/anonymous.
 */
public class ConnectionInfo {

    private final String endpoint;
    private final String securityPolicy;
    private final String messageMode;
    private final JsonObject raw;

    public ConnectionInfo(JsonObject o) {
        this.raw = o != null ? o : new JsonObject();
        this.endpoint = raw.has("endpoint") ? raw.get("endpoint").getAsString() : "";
        this.securityPolicy = raw.has("securityPolicy") ? raw.get("securityPolicy").getAsString() : "None";
        this.messageMode = raw.has("messageMode") ? raw.get("messageMode").getAsString() : "None";
    }

    public String getEndpoint() {
        return endpoint;
    }

    /** Milo {@code SecurityPolicy} enum name, e.g. {@code None} or {@code Basic256Sha256}. */
    public String getSecurityPolicy() {
        return securityPolicy;
    }

    /** Milo {@code MessageSecurityMode} enum name, e.g. {@code None}, {@code Sign}, {@code SignAndEncrypt}. */
    public String getMessageMode() {
        return messageMode;
    }

    /** Whether this connection requests a secure channel (anything other than {@code None}). */
    public boolean isSecure() {
        return !"None".equalsIgnoreCase(securityPolicy);
    }

    /** The raw connection JSON, for the security layer to read cert-source fields from. */
    public JsonObject raw() {
        return raw;
    }
}
