package com.mbreissi.edgecommons.opcua.opc.security;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.credentials.BasicAuth;
import com.mbreissi.edgecommons.credentials.CredentialService;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;

/**
 * Builds the Milo {@link IdentityProvider} for a connection from its optional {@code user} block.
 * Applies to any security policy (a {@code None} channel can still require a UserName token, as
 * KEPServerEX does by default).
 *
 * <ul>
 *   <li>no {@code user} → {@link AnonymousProvider} (the default)</li>
 *   <li>{@code "user": { "username": "...", "password": "..." }} → inline {@link UsernameProvider}</li>
 *   <li>{@code "user": { "source": "vault", "secret": "opcua/&lt;id&gt;/login" }} → vault-backed
 *       {@link UsernameProvider}; the secret is a {@code BasicAuth} {@code {username, password}}.</li>
 * </ul>
 *
 * The password is never logged; use {@link #describe(JsonObject)} for a non-sensitive label.
 */
public final class IdentityProviders {

    private IdentityProviders() {
    }

    /** Build the identity provider, throwing on an incomplete or unresolvable {@code user} block. */
    public static IdentityProvider from(JsonObject user, CredentialService credentials) {
        if (user == null || user.size() == 0) {
            return new AnonymousProvider();
        }
        String source = user.has("source") ? user.get("source").getAsString() : "inline";
        if ("vault".equalsIgnoreCase(source)) {
            if (!user.has("secret")) {
                throw new IllegalArgumentException("connection.user source 'vault' requires 'secret'");
            }
            if (credentials == null) {
                throw new IllegalStateException(
                        "connection.user uses the vault but no credentials subsystem is configured");
            }
            String secret = user.get("secret").getAsString();
            BasicAuth ba = credentials.getBasicAuth(secret).orElseThrow(() -> new IllegalArgumentException(
                    "vault secret '" + secret + "' is not basic auth {username, password}"));
            return new UsernameProvider(ba.username(), ba.password());
        }
        if (!user.has("username")) {
            throw new IllegalArgumentException(
                    "connection.user requires 'username' (or source 'vault' + 'secret')");
        }
        String username = user.get("username").getAsString();
        String password = user.has("password") ? user.get("password").getAsString() : "";
        return new UsernameProvider(username, password);
    }

    /** A non-sensitive one-line description of the identity for logging (never the password). */
    public static String describe(JsonObject user) {
        if (user == null || user.size() == 0) {
            return "anonymous";
        }
        String source = user.has("source") ? user.get("source").getAsString() : "inline";
        if ("vault".equalsIgnoreCase(source)) {
            return "username(vault:" + (user.has("secret") ? user.get("secret").getAsString() : "?") + ")";
        }
        return "username(" + (user.has("username") ? user.get("username").getAsString() : "?") + ")";
    }
}
