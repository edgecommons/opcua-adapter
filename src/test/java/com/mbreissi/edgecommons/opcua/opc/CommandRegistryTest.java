package com.mbreissi.edgecommons.opcua.opc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the {@link CommandRegistry} wiring shell that can run without a live inbox: it holds a
 * {@link CommandRouter}, and {@code registerVerbs()} is a safe no-op when the inbox is absent (a
 * test/mock bring-up with no resolved identity) so the adapter still comes up. Registering on a live
 * inbox is validated on real infrastructure.
 */
class CommandRegistryTest {

    @Test
    void exposesARouter() {
        CommandRegistry registry = new CommandRegistry(null);
        assertNotNull(registry.router());
    }

    @Test
    void registerVerbs_withoutInbox_isASafeNoOp() {
        CommandRegistry registry = new CommandRegistry(null);
        // No inbox available -> logs a warning and returns; must not throw.
        registry.registerVerbs();
    }
}
