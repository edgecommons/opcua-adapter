package com.mbreissi.opcua.opcuaadapter.opc.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mbreissi.ggcommons.config.ConfigManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the UNS-migration config surface of {@link ServerConfiguration}: the D‑U16
 * {@code writes.allow[]} allow-list that replaced the boolean {@code write.enabled}. Uses a thin
 * {@link ConfigManager} subclass returning a crafted instance document — no live OPC UA server.
 */
class ServerConfigurationTest {

    /** A ConfigManager whose {@code getInstanceConfig} returns the supplied JSON for any instance id. */
    private static ConfigManager configManagerFor(String instanceJson) {
        JsonObject inst = JsonParser.parseString(instanceJson).getAsJsonObject();
        return new ConfigManager() {
            @Override
            public JsonObject getInstanceConfig(String instanceId) {
                return inst;
            }
        };
    }

    private static ServerConfiguration serverConfig(String instanceJson) {
        return new ServerConfiguration(configManagerFor(instanceJson), new JsonObject(), "kep1");
    }

    @Test
    void writesAllow_absent_rejectsEveryWrite() {
        ServerConfiguration cfg = serverConfig("{\"id\":\"kep1\"}");
        assertFalse(cfg.isWriteConfigured());
        assertFalse(cfg.isWriteAllowed("ns=2;s=Channel1.Device1.Setpoint"));
    }

    @Test
    void writesAllow_listed_allowsOnlyExactStableIds() {
        ServerConfiguration cfg = serverConfig(
                "{\"id\":\"kep1\",\"writes\":{\"allow\":[\"ns=2;s=Channel1.Device1.Setpoint\",\"ns=3;i=1001\"]}}");
        assertTrue(cfg.isWriteConfigured());
        assertTrue(cfg.isWriteAllowed("ns=2;s=Channel1.Device1.Setpoint"));
        assertTrue(cfg.isWriteAllowed("ns=3;i=1001"));
        // A near-miss (different id / different namespace) is rejected.
        assertFalse(cfg.isWriteAllowed("ns=2;s=Channel1.Device1.Other"));
        assertFalse(cfg.isWriteAllowed("ns=4;i=1001"));
    }

    @Test
    void writesAllow_wildcard_allowsEverything() {
        ServerConfiguration cfg = serverConfig("{\"id\":\"kep1\",\"writes\":{\"allow\":[\"*\"]}}");
        assertTrue(cfg.isWriteConfigured());
        assertTrue(cfg.isWriteAllowed("ns=2;s=anything"));
        assertTrue(cfg.isWriteAllowed("ns=9;i=42"));
    }

    @Test
    void publishBatchMs_readFromPublishBlock_defaultsToPublishInterval() {
        ServerConfiguration explicit = serverConfig("{\"id\":\"kep1\",\"publish\":{\"batchMs\":250}}");
        org.junit.jupiter.api.Assertions.assertEquals(250L, explicit.getBatchMs());
        // Absent batchMs falls back to the resolved instance publishIntervalMs (default 1000).
        ServerConfiguration dflt = serverConfig("{\"id\":\"kep1\"}");
        org.junit.jupiter.api.Assertions.assertEquals(1000L, dflt.getBatchMs());
    }
}
