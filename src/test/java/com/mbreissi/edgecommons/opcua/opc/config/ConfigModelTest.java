package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.opcua.opc.ResolvedSignal;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DeadbandType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure OPC UA config model: {@link ConnectionInfo}, {@link DeadbandSpec},
 * {@link SignalSpec}, {@link SubscriptionSpec} (with {@link IncludeSpec}/{@link ExcludeSpec}), and the
 * full {@link ServerConfiguration} parse — no live server.
 */
class ConfigModelTest {

    private static ConfigManager configManagerFor(String instanceJson) {
        JsonObject inst = JsonParser.parseString(instanceJson).getAsJsonObject();
        return new ConfigManager() {
            @Override
            public JsonObject getInstanceConfig(String instanceId) {
                return inst;
            }
        };
    }

    // ---- ConnectionInfo --------------------------------------------------------------------------

    @Test
    void connectionInfo_defaultsToAnonymousNone() {
        ConnectionInfo c = new ConnectionInfo(null);
        assertEquals("", c.getEndpoint());
        assertEquals("None", c.getSecurityPolicy());
        assertEquals("None", c.getMessageMode());
        assertFalse(c.isSecure());
        assertNull(c.getUser());
    }

    @Test
    void connectionInfo_secureWithUserBlock() {
        JsonObject o = JsonParser.parseString("{\"endpoint\":\"opc.tcp://h:4840/\","
                + "\"securityPolicy\":\"Basic256Sha256\",\"messageMode\":\"SignAndEncrypt\","
                + "\"user\":{\"username\":\"u\",\"password\":\"p\"}}").getAsJsonObject();
        ConnectionInfo c = new ConnectionInfo(o);
        assertEquals("opc.tcp://h:4840/", c.getEndpoint());
        assertEquals("Basic256Sha256", c.getSecurityPolicy());
        assertEquals("SignAndEncrypt", c.getMessageMode());
        assertTrue(c.isSecure());
        assertNotNull(c.getUser());
        assertNotNull(c.raw());
    }

    // ---- DeadbandSpec ----------------------------------------------------------------------------

    @Test
    void deadbandSpec_parsesTypesAndDefaults() {
        assertEquals(DeadbandType.None, DeadbandSpec.fromJson(null).getType());
        DeadbandSpec abs = DeadbandSpec.fromJson(
                JsonParser.parseString("{\"type\":\"Absolute\",\"value\":0.5}").getAsJsonObject());
        assertEquals(DeadbandType.Absolute, abs.getType());
        assertEquals(0.5, abs.getValue());
    }

    // ---- SignalSpec ------------------------------------------------------------------------------

    @Test
    void signalSpec_getterAndCompiledPattern() {
        SignalSpec s = SignalSpec.fromJson(
                JsonParser.parseString("{\"namespace\":2,\"match\":\"^Sim\\\\.Sine.*\","
                        + "\"samplingRateMs\":50,\"queueSize\":10}").getAsJsonObject());
        assertEquals(2, s.getNamespace());
        assertEquals("^Sim\\.Sine.*", s.getMatch());
        assertEquals(50.0, s.getSamplingRateMs());
        assertEquals(10, s.getQueueSize());
        assertNull(s.getNamespaceUri());
        assertNull(s.getTopic());
        assertTrue(s.pattern().matcher("Sim.Sine1").matches());
        // cached compile returns the same instance
        assertEquals(s.pattern(), s.pattern());
    }

    @Test
    void signalSpec_defaultsMatchAllWhenAbsent() {
        SignalSpec s = SignalSpec.fromJson(new JsonObject());
        assertEquals(".*", s.getMatch());
        assertEquals(DeadbandType.None, s.getDeadband().getType());
    }

    // ---- ServerConfiguration full parse (SubscriptionSpec/Include/Exclude) -----------------------

    @Test
    void serverConfiguration_parsesConnectionDefaultsAndSubscriptions() {
        String json = "{"
                + "\"id\":\"kep1\","
                + "\"connection\":{\"endpoint\":\"opc.tcp://h:4840/\",\"securityPolicy\":\"None\"},"
                + "\"defaults\":{\"publishIntervalMs\":500,\"samplingRateMs\":250,\"queueSize\":50},"
                + "\"publish\":{\"batchMs\":800},"
                + "\"writes\":{\"allow\":[\"ns=2;s=A\"]},"
                + "\"subscriptions\":[{"
                + "  \"id\":\"sine\","
                + "  \"include\":[{\"namespace\":2,\"match\":\"^Sim\\\\.Sine.*\",\"deadband\":{\"type\":\"Absolute\",\"value\":0.5}}],"
                + "  \"exclude\":[{\"namespace\":2,\"match\":\"Sim\\\\.Sine4\"}]"
                + "}]}";
        ServerConfiguration cfg = new ServerConfiguration(configManagerFor(json), new JsonObject(), "kep1");

        assertEquals("kep1", cfg.getId());
        assertEquals("opc.tcp://h:4840/", cfg.getConnection().getEndpoint());
        assertEquals(500.0, cfg.getDefaultPublishIntervalMs());
        assertEquals(250.0, cfg.getDefaultSamplingMs());
        assertEquals(50, cfg.getDefaultQueueSize());
        assertEquals(800L, cfg.getBatchMs());
        assertTrue(cfg.isWriteAllowed("ns=2;s=A"));

        List<SubscriptionSpec> subs = cfg.getSubscriptionSpecs();
        assertEquals(1, subs.size());
        SubscriptionSpec sub = subs.get(0);
        assertEquals("sine", sub.getId());
        assertEquals(500.0, sub.getPublishIntervalMs());
        assertEquals(1, sub.getIncludeSpec().getSignalSpecs().size());
        assertEquals(DeadbandType.Absolute,
                sub.getIncludeSpec().getSignalSpecs().get(0).getDeadband().getType());
        assertEquals(1, sub.getExcludeSpec().getSignalSpecs().size());
    }

    @Test
    void serverConfiguration_globalDefaultsUsedWhenInstanceOmits() {
        JsonObject global = JsonParser.parseString(
                "{\"defaults\":{\"publishIntervalMs\":2000}}").getAsJsonObject();
        ServerConfiguration cfg = new ServerConfiguration(
                configManagerFor("{\"id\":\"kep1\"}"), global, "kep1");
        assertEquals(2000.0, cfg.getDefaultPublishIntervalMs());
        // batchMs falls back to the resolved publishIntervalMs when publish.batchMs is absent
        assertEquals(2000L, cfg.getBatchMs());
    }

    // ---- ResolvedSignal --------------------------------------------------------------------------

    @Test
    void resolvedSignal_carriesNodeIdAndSpec() {
        SignalSpec spec = SignalSpec.fromJson(
                JsonParser.parseString("{\"namespace\":2,\"match\":\".*\"}").getAsJsonObject());
        NodeId nodeId = new NodeId(2, "Channel1.Device1.Tag");
        ResolvedSignal rs = new ResolvedSignal(nodeId, spec);
        assertEquals(nodeId, rs.nodeId());
        assertEquals(spec, rs.spec());
    }
}
