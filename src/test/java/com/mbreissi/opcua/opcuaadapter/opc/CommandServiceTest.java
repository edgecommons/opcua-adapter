package com.mbreissi.opcua.opcuaadapter.opc;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandServiceTest {

    // ---- mergeReadTargets (read explicit + include dedup) ----------------------------------------

    @Test
    void mergeReadTargets_explicitOnly_preservesOrderAndDuplicates() {
        NodeId x = new NodeId(2, "X");
        NodeId y = new NodeId(2, "Y");
        // A duplicated explicit signalId must stay duplicated (explicit-only path == pre-change).
        List<NodeId> result = CommandService.mergeReadTargets(List.of(x, x, y), List.of());
        assertEquals(List.of(x, x, y), result);
    }

    @Test
    void mergeReadTargets_includeDedupedAgainstExplicitAndAppendedAfter() {
        NodeId x = new NodeId(2, "X");
        NodeId y = new NodeId(2, "Y");
        NodeId z = new NodeId(2, "Z");
        // X is already explicit → include's X is dropped; new include nodes append in order.
        List<NodeId> result = CommandService.mergeReadTargets(List.of(x), List.of(x, y, z));
        assertEquals(List.of(x, y, z), result);
    }

    @Test
    void mergeReadTargets_includeDedupedAmongThemselves() {
        NodeId y = new NodeId(2, "Y");
        List<NodeId> result = CommandService.mergeReadTargets(List.of(), List.of(y, y));
        assertEquals(List.of(y), result);
    }

    // ---- ValueCodec node-id round-trip (idType) --------------------------------------------------

    @Test
    void nodeId_numeric_roundTripsThroughBareIdentifierAndIdType() {
        NodeId numeric = ValueCodec.nodeId(2, "1001", "Numeric");
        assertEquals("Numeric", ValueCodec.idTypeName(numeric));
        assertEquals("1001", numeric.getIdentifier().toString());
        // A String-typed node with the same bare identifier is a DIFFERENT node.
        assertEquals("String", ValueCodec.idTypeName(new NodeId(2, "1001")));
    }

    @Test
    void nodeId_guid_roundTrips() {
        String uuid = "12345678-1234-1234-1234-123456789abc";
        NodeId guid = ValueCodec.nodeId(2, uuid, "Guid");
        assertEquals("Guid", ValueCodec.idTypeName(guid));
        assertEquals(UUID.fromString(uuid), guid.getIdentifier());
    }

    @Test
    void nodeId_nullOrStringIdType_buildsStringIdentifier() {
        assertEquals("String", ValueCodec.idTypeName(ValueCodec.nodeId(2, "Foo", null)));
        assertEquals("String", ValueCodec.idTypeName(ValueCodec.nodeId(2, "Foo", "String")));
        assertEquals("Foo", ValueCodec.nodeId(2, "Foo", null).getIdentifier().toString());
    }
}
