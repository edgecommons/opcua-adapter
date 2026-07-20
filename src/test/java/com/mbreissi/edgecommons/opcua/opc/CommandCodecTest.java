package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mbreissi.edgecommons.commands.CommandException;
import com.mbreissi.edgecommons.messaging.Message;
import com.mbreissi.edgecommons.messaging.MessageBuilder;
import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the client-free command helpers extracted from {@link CommandService}/
 * {@link CommandRegistry}: request-arg clamping, the string {@code sb/browse} root-ref resolution
 * (incl. the {@code BAD_ARGS} failure), the explicit/include read-target merge, the request-body
 * extraction, and the signal-spec parse.
 */
class CommandCodecTest {

    // ---- boundedInt ------------------------------------------------------------------------------

    @Test
    void boundedInt_clampsAndDefaults() {
        JsonObject req = new JsonObject();
        assertEquals(1, CommandCodec.boundedInt(req, "depth", 1, 0, 4)); // absent -> default
        req.addProperty("depth", 99);
        assertEquals(4, CommandCodec.boundedInt(req, "depth", 1, 0, 4)); // clamp high
        req.addProperty("depth", -3);
        assertEquals(0, CommandCodec.boundedInt(req, "depth", 1, 0, 4)); // clamp low
        assertEquals(7, CommandCodec.boundedInt(null, "depth", 7, 0, 4)); // null body -> default
    }

    // ---- browseRootRef ---------------------------------------------------------------------------

    @Test
    void browseRootRef_resolvesWellKnownRoots() throws Exception {
        assertEquals(NodeIds.RootFolder, CommandCodec.browseRootRef(null));
        assertEquals(NodeIds.RootFolder, CommandCodec.browseRootRef(""));
        assertEquals(NodeIds.RootFolder, CommandCodec.browseRootRef("root"));
        assertEquals(NodeIds.ObjectsFolder, CommandCodec.browseRootRef("objects"));
        assertEquals(NodeIds.TypesFolder, CommandCodec.browseRootRef("Types"));
        assertEquals(NodeIds.ViewsFolder, CommandCodec.browseRootRef("views"));
    }

    @Test
    void browseRootRef_parsesNodeId() throws Exception {
        assertEquals(new NodeId(2, "Channel1.Device1.Tag"),
                CommandCodec.browseRootRef("ns=2;s=Channel1.Device1.Tag"));
    }

    @Test
    void browseRootRef_malformed_isBadArgs() {
        CommandException e = assertThrows(CommandException.class,
                () -> CommandCodec.browseRootRef("not-a-node-id"));
        assertEquals(CommandRouter.ERR_BAD_ARGS, e.getCode());
    }

    // ---- mergeReadTargets ------------------------------------------------------------------------

    @Test
    void mergeReadTargets_explicitOnly_preservesOrderAndDuplicates() {
        NodeId x = new NodeId(2, "X");
        NodeId y = new NodeId(2, "Y");
        assertEquals(List.of(x, x, y), CommandCodec.mergeReadTargets(List.of(x, x, y), List.of()));
    }

    @Test
    void mergeReadTargets_includeDedupedAgainstExplicitAndAppended() {
        NodeId x = new NodeId(2, "X");
        NodeId y = new NodeId(2, "Y");
        NodeId z = new NodeId(2, "Z");
        assertEquals(List.of(x, y, z), CommandCodec.mergeReadTargets(List.of(x), List.of(x, y, z)));
        assertEquals(List.of(y), CommandCodec.mergeReadTargets(List.of(), List.of(y, y)));
    }

    // ---- specsFromJson ---------------------------------------------------------------------------

    @Test
    void specsFromJson_parsesEachMatcher() {
        JsonArray arr = JsonParser.parseString(
                "[{\"namespace\":2,\"match\":\"^Sim\\\\.Sine.*\"},{\"namespace\":3,\"match\":\".*\"}]")
                .getAsJsonArray();
        List<SignalSpec> specs = CommandCodec.specsFromJson(arr);
        assertEquals(2, specs.size());
        assertEquals(2, specs.get(0).getNamespace());
        assertEquals("^Sim\\.Sine.*", specs.get(0).getMatch());
    }

    // ---- bodyOf ----------------------------------------------------------------------------------

    @Test
    void bodyOf_returnsJsonObjectBody_elseEmpty() {
        JsonObject body = new JsonObject();
        body.addProperty("instance", "kep1");
        Message m = MessageBuilder.create("sb/status", "1.0").withPayload(body).build();
        assertEquals("kep1", CommandCodec.bodyOf(m).get("instance").getAsString());
        // null request -> empty object
        assertEquals(0, CommandCodec.bodyOf(null).size());
    }
}
