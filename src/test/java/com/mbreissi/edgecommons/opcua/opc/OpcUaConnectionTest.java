package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpcUaConnectionTest {

    @Test
    void invalidCredentialsAreTerminal() {
        assertTrue(OpcUaConnection.isUnretryableConnectionFailure(
                new UaException(StatusCodes.Bad_UserAccessDenied)));
        assertTrue(OpcUaConnection.isUnretryableConnectionFailure(
                new UaException(StatusCodes.Bad_IdentityTokenRejected)));
    }

    @Test
    void securityAndConfigurationFailuresAreTerminal() {
        assertTrue(OpcUaConnection.isUnretryableConnectionFailure(
                new UaException(StatusCodes.Bad_SecurityPolicyRejected)));
        assertTrue(OpcUaConnection.isUnretryableConnectionFailure(
                new UaException(StatusCodes.Bad_CertificateUntrusted)));
        assertTrue(OpcUaConnection.isUnretryableConnectionFailure(
                new UaException(StatusCodes.Bad_ConfigurationError)));
    }

    @Test
    void transportFailuresRemainRetryable() {
        assertFalse(OpcUaConnection.isUnretryableConnectionFailure(new RuntimeException("connection refused")));
    }
}
