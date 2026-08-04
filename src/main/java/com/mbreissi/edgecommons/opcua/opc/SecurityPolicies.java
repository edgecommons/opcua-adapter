package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Fail-closed parsing of the {@code connection.securityPolicy} / {@code connection.messageMode} pair.
 *
 * <p>An unrecognized policy name is a <b>configuration error</b>, never a silent downgrade: a typo in
 * {@code Basic256Sha256} must not connect the adapter to an unauthenticated {@code None} endpoint of a
 * server that happens to expose one. Both parsers throw {@link IllegalArgumentException}; the
 * connection layer maps that onto its unretryable-failure path so the instance never starts.
 *
 * <p>Pure and client-free — the value logic lives here (in the coverage gate) rather than in the live
 * Milo driver seam.
 */
public final class SecurityPolicies {

    private SecurityPolicies() {
    }

    /**
     * Resolve a Milo {@link SecurityPolicy} enum name. Case-sensitive, matching the enum and the
     * {@code config.schema.json} enum.
     *
     * @throws IllegalArgumentException when the name is null, blank, or not a Milo policy
     */
    public static SecurityPolicy parsePolicy(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "connection.securityPolicy is required; valid values are " + validPolicies());
        }
        try {
            return SecurityPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid connection.securityPolicy '" + name
                    + "'; valid values are " + validPolicies());
        }
    }

    /**
     * Resolve a Milo {@link MessageSecurityMode} enum name.
     *
     * @throws IllegalArgumentException when the name is null, blank, or not a Milo mode
     */
    public static MessageSecurityMode parseMode(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "connection.messageMode is required; valid values are None, Sign, SignAndEncrypt");
        }
        try {
            return MessageSecurityMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid connection.messageMode '" + name
                    + "'; valid values are None, Sign, SignAndEncrypt");
        }
    }

    /**
     * Cross-check the pair: a secure policy with {@code messageMode: None} is contradictory — it asks
     * for a cipher suite and then declines to sign or encrypt with it. Rejected rather than quietly
     * negotiated down.
     *
     * @throws IllegalArgumentException when the combination is contradictory
     */
    public static void checkCombination(SecurityPolicy policy, MessageSecurityMode mode) {
        if (policy != SecurityPolicy.None && mode == MessageSecurityMode.None) {
            throw new IllegalArgumentException("connection.securityPolicy '" + policy.name()
                    + "' requires connection.messageMode Sign or SignAndEncrypt, but messageMode is None");
        }
        if (policy == SecurityPolicy.None && mode != MessageSecurityMode.None) {
            throw new IllegalArgumentException("connection.messageMode '" + mode.name()
                    + "' requires a secure connection.securityPolicy, but securityPolicy is None");
        }
    }

    /** The valid policy names, for error messages and the config schema. */
    public static String validPolicies() {
        return Arrays.stream(SecurityPolicy.values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
