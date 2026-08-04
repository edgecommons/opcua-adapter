package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stable-identity contract: a signal is named by its namespace <b>URI</b>, never by the volatile
 * per-session namespace index.
 */
class CanonicalSignalIdTest {

    private static final String SIM = "urn:edgecommons:sim";
    private static final String KEP = "Kepware Server";

    private static NamespaceTable table(String... uris) {
        NamespaceTable t = new NamespaceTable();
        for (String uri : uris) {
            t.add(uri);
        }
        return t;
    }

    // ---- construction from a live node id ---------------------------------------------------------

    @Test
    void of_resolvesNamespaceIndexToUri() {
        NamespaceTable t = table(SIM);
        CanonicalSignalId id = CanonicalSignalId.of(new NodeId(1, "Sine1"), t);
        assertEquals(SIM, id.namespaceUri());
        assertEquals('s', id.idType());
        assertEquals("Sine1", id.identifier());
        assertEquals("nsu=urn:edgecommons:sim;s=Sine1", id.toString());
    }

    @Test
    void of_namespaceZero_keepsTheLiteralForm() {
        CanonicalSignalId id = CanonicalSignalId.of(new NodeId(0, Unsigned.uint(2258)), table());
        assertTrue(id.isNamespaceZero());
        assertNull(id.namespaceUri());
        assertEquals("ns=0;i=2258", id.toString());
        assertEquals("ns0_i_2258", id.channelToken());
    }

    @Test
    void of_unknownNamespaceIndex_refusesToInventAnIdentity() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CanonicalSignalId.of(new NodeId(7, "Orphan"), table(SIM)));
        assertTrue(e.getMessage().contains("not in the server's namespace table"));
    }

    @Test
    void idType_coversEveryOpcUaIdentifierKind() {
        NamespaceTable t = table(SIM);
        assertEquals('s', CanonicalSignalId.of(new NodeId(1, "Text"), t).idType());
        assertEquals('i', CanonicalSignalId.of(new NodeId(1, Unsigned.uint(42)), t).idType());
        assertEquals('g', CanonicalSignalId.of(new NodeId(1, UUID.randomUUID()), t).idType());
    }

    // ---- the property that motivates the whole type -----------------------------------------------

    /**
     * The same physical node keeps one identity when the server renumbers its namespace table between
     * sessions — the failure that let an allow-list entry authorize the wrong node.
     */
    @Test
    void identity_survivesNamespaceRenumbering() {
        CanonicalSignalId before = CanonicalSignalId.of(new NodeId(1, "Setpoint"), table(SIM, KEP));
        // Next session: the server declares the same namespaces in the opposite order.
        CanonicalSignalId after = CanonicalSignalId.of(new NodeId(2, "Setpoint"), table(KEP, SIM));
        assertEquals(before, after);
        assertEquals(before.toString(), after.toString());
    }

    /** Two namespaces exposing the same identifier are distinct identities and distinct channels. */
    @Test
    void identity_separatesNamespacesSharingAnIdentifier() {
        NamespaceTable t = table(SIM, KEP);
        CanonicalSignalId a = CanonicalSignalId.of(new NodeId(1, "Setpoint"), t);
        CanonicalSignalId b = CanonicalSignalId.of(new NodeId(2, "Setpoint"), t);
        assertNotEquals(a, b);
        assertNotEquals(a.channelToken(), b.channelToken());
    }

    /** {@code i=42} and {@code s=42} are different nodes, so they must not share a channel. */
    @Test
    void channelToken_separatesIdentifierTypes() {
        NamespaceTable t = table(SIM);
        String numeric = CanonicalSignalId.of(new NodeId(1, Unsigned.uint(42)), t).channelToken();
        String text = CanonicalSignalId.of(new NodeId(1, "42"), t).channelToken();
        assertNotEquals(numeric, text);
    }

    // ---- channel token ----------------------------------------------------------------------------

    @Test
    void channelToken_isTheDocumentedShape() {
        assertEquals("uea03203e_s_Sine1", new CanonicalSignalId(SIM, 's', "Sine1").channelToken());
        assertEquals("u2511aeb1_s_EdgeCommonsTest.Device1.LiveSine",
                new CanonicalSignalId(KEP, 's', "EdgeCommonsTest.Device1.LiveSine").channelToken());
        assertEquals("u2511aeb1_i_1001", new CanonicalSignalId(KEP, 'i', "1001").channelToken());
    }

    /** The token is opaque to whatever the vendor called its namespace — spaces and slashes included. */
    @Test
    void namespaceToken_isFreeOfVendorText() {
        String token = new CanonicalSignalId(KEP, 's', "T").namespaceToken();
        assertFalse(token.contains(" "));
        assertEquals(9, token.length());
        assertTrue(token.startsWith("u"));
    }

    @Test
    void namespaceToken_isStableForTheSameUri() {
        assertEquals(new CanonicalSignalId(SIM, 's', "A").namespaceToken(),
                new CanonicalSignalId(SIM, 'i', "9").namespaceToken());
    }

    // ---- parsing ----------------------------------------------------------------------------------

    @Test
    void parse_roundTripsTheCanonicalForm() {
        CanonicalSignalId id = CanonicalSignalId.parse("nsu=urn:edgecommons:sim;s=Sine1");
        assertEquals(new CanonicalSignalId(SIM, 's', "Sine1"), id);
        assertEquals("nsu=urn:edgecommons:sim;s=Sine1", id.toString());
    }

    @Test
    void parse_acceptsAUriContainingSpacesAndColons() {
        CanonicalSignalId id = CanonicalSignalId.parse("nsu=Kepware Server;s=A.B.C");
        assertEquals(KEP, id.namespaceUri());
        assertEquals("A.B.C", id.identifier());
    }

    @Test
    void parse_acceptsNamespaceZero() {
        CanonicalSignalId id = CanonicalSignalId.parse("ns=0;i=2258");
        assertTrue(id.isNamespaceZero());
        assertEquals("2258", id.identifier());
    }

    /** The central refusal: an index-bearing id names nothing stable, so it is not accepted anywhere. */
    @Test
    void parse_rejectsANamespaceIndex_withAnActionableMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CanonicalSignalId.parse("ns=2;s=Setpoint"));
        assertTrue(e.getMessage().contains("not stable across sessions"));
        assertTrue(e.getMessage().contains("nsu=<namespaceUri>;s=Setpoint"));
    }

    @Test
    void parse_rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalSignalId.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSignalId.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSignalId.parse("Sine1"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSignalId.parse("nsu=;s=A"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSignalId.parse("nsu=urn:x;A"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalSignalId.parse("weird=1;s=A"));
    }

    @Test
    void construction_rejectsAnUnknownIdentifierType() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalSignalId(SIM, 'x', "A"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalSignalId(SIM, 's', null));
    }

    // ---- resolving back to a live node id ---------------------------------------------------------

    @Test
    void toNodeId_resolvesAgainstTheCurrentTable() {
        NamespaceTable t = table(KEP, SIM);
        NodeId nodeId = new CanonicalSignalId(SIM, 's', "Sine1").toNodeId(t);
        assertEquals(2, nodeId.getNamespaceIndex().intValue());
        assertEquals("Sine1", nodeId.getIdentifier().toString());
    }

    @Test
    void toNodeId_numericAndNamespaceZero() {
        NodeId nodeId = new CanonicalSignalId(null, 'i', "2258").toNodeId(table());
        assertEquals(0, nodeId.getNamespaceIndex().intValue());
        assertEquals(Unsigned.uint(2258), nodeId.getIdentifier());
    }

    @Test
    void toNodeId_returnsNullWhenTheServerNoLongerExposesTheNamespace() {
        assertNull(new CanonicalSignalId("urn:gone", 's', "A").toNodeId(table(SIM)));
    }

    @Test
    void toNodeId_roundTripsFromOf() {
        NamespaceTable t = table(SIM);
        NodeId original = new NodeId(1, "Sine1");
        assertEquals(original, CanonicalSignalId.of(original, t).toNodeId(t));
    }
}
