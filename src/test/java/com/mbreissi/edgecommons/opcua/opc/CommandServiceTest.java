package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The remaining CommandService-adjacent pure checks: the OPC UA node-id round-trip through
 * {@link ValueCodec} that read/write refs depend on. (The client-free command helpers moved to
 * {@link CommandCodec}; the client-touching read/write/browse engine is validated by {@code validation/}
 * against a real server.)
 */
class CommandServiceTest {

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
