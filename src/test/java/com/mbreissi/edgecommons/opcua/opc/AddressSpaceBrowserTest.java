package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mbreissi.edgecommons.opcua.opc.config.AdapterLimits;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address-space traversal contract, driven through a scripted {@link BrowseTransport}: cycles
 * terminate, budgets bound the walk, continuation points are consumed and released, and a failure is
 * reported as an incomplete outcome rather than a silently partial map.
 */
class AddressSpaceBrowserTest {

    /** A scripted server: each node id maps to one or more browse pages. */
    private static final class FakeTransport implements BrowseTransport {
        final Map<NodeId, List<BrowseResult>> pages = new HashMap<>();
        final Map<ByteString, BrowseResult> continuations = new HashMap<>();
        final List<ByteString> released = new ArrayList<>();
        final List<NodeId> browsed = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public BrowseResult browseNode(NodeId nodeId) {
            browsed.add(nodeId);
            if (failWith != null) {
                throw failWith;
            }
            List<BrowseResult> results = pages.get(nodeId);
            return results == null || results.isEmpty() ? empty() : results.get(0);
        }

        @Override
        public BrowseResult continueBrowse(ByteString continuationPoint) {
            return continuations.get(continuationPoint);
        }

        @Override
        public void releaseContinuationPoint(ByteString continuationPoint) {
            released.add(continuationPoint);
        }

        @Override
        public UaVariableNode resolveVariable(NodeId nodeId) {
            // The traversal's own bookkeeping is what is under test; node materialization is the live
            // seam. A non-null marker would need a live client, so record discovery via `browsed`.
            return null;
        }

        @Override
        public NamespaceTable namespaceTable() {
            return new NamespaceTable();
        }
    }

    private static BrowseResult empty() {
        return new BrowseResult(StatusCode.GOOD, ByteString.NULL_VALUE, new ReferenceDescription[0]);
    }

    private static BrowseResult page(ByteString continuationPoint, ReferenceDescription... refs) {
        return new BrowseResult(StatusCode.GOOD, continuationPoint, refs);
    }

    private static ReferenceDescription ref(NodeId target, NodeClass nodeClass) {
        return new ReferenceDescription(NodeIds.HierarchicalReferences, true,
                ExpandedNodeId.of(target.getIdentifier().toString()),
                new QualifiedName(0, target.getIdentifier().toString()),
                LocalizedText.english(target.getIdentifier().toString()), nodeClass,
                ExpandedNodeId.NULL_VALUE);
    }

    private static NodeId obj(String id) {
        return new NodeId(0, id);
    }

    private static AddressSpaceBrowser browser(FakeTransport t, AdapterLimits limits) {
        return new AddressSpaceBrowser(t, "test", limits);
    }

    // ---- cycles ----------------------------------------------------------------------------------

    /**
     * OPC UA address spaces may contain reference cycles. The previous recursive walk followed them
     * until the stack overflowed; the traversal must simply terminate.
     */
    @Test
    void cyclicAddressSpace_terminates() {
        FakeTransport t = new FakeTransport();
        NodeId a = obj("A");
        NodeId b = obj("B");
        t.pages.put(NodeIds.RootFolder, List.of(page(ByteString.NULL_VALUE, ref(a, NodeClass.Object))));
        t.pages.put(a, List.of(page(ByteString.NULL_VALUE, ref(b, NodeClass.Object))));
        t.pages.put(b, List.of(page(ByteString.NULL_VALUE, ref(a, NodeClass.Object))));   // back-edge

        BrowseOutcome outcome = browser(t, AdapterLimits.defaults()).browseAll();

        assertTrue(outcome.complete());
        // Each node is browsed exactly once despite the cycle.
        assertEquals(3, t.browsed.size());
    }

    @Test
    void repeatedReferencesToOneNode_areVisitedOnce() {
        FakeTransport t = new FakeTransport();
        NodeId shared = obj("Shared");
        t.pages.put(NodeIds.RootFolder, List.of(page(ByteString.NULL_VALUE,
                ref(shared, NodeClass.Object), ref(shared, NodeClass.Object))));
        t.pages.put(shared, List.of(empty()));

        browser(t, AdapterLimits.defaults()).browseAll();

        assertEquals(1, t.browsed.stream().filter(shared::equals).count());
    }

    // ---- continuation points ---------------------------------------------------------------------

    /** A paged browse must be followed to the end, or the address space is silently truncated. */
    @Test
    void continuationPoint_isFollowedUntilExhausted() {
        FakeTransport t = new FakeTransport();
        ByteString cp = ByteString.of("page2".getBytes(StandardCharsets.UTF_8));
        NodeId first = obj("First");
        NodeId second = obj("Second");
        t.pages.put(NodeIds.RootFolder, List.of(page(cp, ref(first, NodeClass.Object))));
        t.continuations.put(cp, page(ByteString.NULL_VALUE, ref(second, NodeClass.Object)));
        t.pages.put(first, List.of(empty()));
        t.pages.put(second, List.of(empty()));

        BrowseOutcome outcome = browser(t, AdapterLimits.defaults()).browseAll();

        assertTrue(outcome.complete());
        assertTrue(t.browsed.contains(second), "the second page's node was never reached");
    }

    /** A continuation point the traversal will not consume must be handed back (Part 4 §5.9.3). */
    @Test
    void continuationPoint_isReleasedWhenTheBudgetStopsTheWalk() {
        FakeTransport t = new FakeTransport();
        ByteString cp = ByteString.of("more".getBytes(StandardCharsets.UTF_8));
        NodeId mid = obj("Mid");
        NodeId deep = obj("Deep");
        t.pages.put(NodeIds.RootFolder, List.of(page(ByteString.NULL_VALUE, ref(mid, NodeClass.Object))));
        // Mid's page both refuses a child (depth budget) and offers a further page.
        t.pages.put(mid, List.of(page(cp, ref(deep, NodeClass.Object))));
        t.continuations.put(cp, empty());

        AdapterLimits limits = AdapterLimits.fromGlobal(json("{\"browse\":{\"maxDepth\":1}}"));
        BrowseOutcome outcome = browser(t, limits).browseAll();

        assertFalse(outcome.complete());
        assertTrue(outcome.truncated());
        assertEquals(List.of(cp), t.released, "the unconsumed continuation point was not released");
    }

    // ---- failure reporting -----------------------------------------------------------------------

    /**
     * The defect this type exists to prevent: a browse failure previously produced an empty map that
     * looked exactly like a legitimately empty server, and {@code sb/rescan} would swap it in.
     */
    @Test
    void browseFailure_isReportedAsIncomplete() {
        FakeTransport t = new FakeTransport();
        t.failWith = new IllegalStateException("session down");

        BrowseOutcome outcome = browser(t, AdapterLimits.defaults()).browseAll();

        assertFalse(outcome.complete());
        assertEquals(1, outcome.errors());
        assertTrue(outcome.nodes().isEmpty());
        assertNotNull(outcome.incompleteReason());
        assertTrue(outcome.incompleteReason().contains("failed"));
    }

    @Test
    void badBrowseStatus_isCountedAndDoesNotAbortTheWalk() {
        FakeTransport t = new FakeTransport();
        NodeId good = obj("Good");
        NodeId bad = obj("Bad");
        t.pages.put(NodeIds.RootFolder, List.of(page(ByteString.NULL_VALUE,
                ref(bad, NodeClass.Object), ref(good, NodeClass.Object))));
        t.pages.put(bad, List.of(new BrowseResult(new StatusCode(0x80000000L), ByteString.NULL_VALUE, null)));
        t.pages.put(good, List.of(empty()));

        BrowseOutcome outcome = browser(t, AdapterLimits.defaults()).browseAll();

        assertFalse(outcome.complete());
        assertEquals(1, outcome.errors());
        assertTrue(t.browsed.contains(good), "a sibling failure aborted the whole traversal");
    }

    @Test
    void emptyServer_isCompleteNotFailed() {
        FakeTransport t = new FakeTransport();
        t.pages.put(NodeIds.RootFolder, List.of(empty()));

        BrowseOutcome outcome = browser(t, AdapterLimits.defaults()).browseAll();

        assertTrue(outcome.complete());
        assertEquals(0, outcome.size());
        assertEquals(null, outcome.incompleteReason());
    }

    @Test
    void nullReferenceArray_isTolerated() {
        FakeTransport t = new FakeTransport();
        t.pages.put(NodeIds.RootFolder, List.of(
                new BrowseResult(StatusCode.GOOD, ByteString.NULL_VALUE, null)));

        assertTrue(browser(t, AdapterLimits.defaults()).browseAll().complete());
    }

    // ---- depth budget ----------------------------------------------------------------------------

    @Test
    void depthBudget_boundsTheDescent() {
        FakeTransport t = new FakeTransport();
        NodeId l1 = obj("L1");
        NodeId l2 = obj("L2");
        t.pages.put(NodeIds.RootFolder, List.of(page(ByteString.NULL_VALUE, ref(l1, NodeClass.Object))));
        t.pages.put(l1, List.of(page(ByteString.NULL_VALUE, ref(l2, NodeClass.Object))));
        t.pages.put(l2, List.of(empty()));

        AdapterLimits limits = AdapterLimits.fromGlobal(json("{\"browse\":{\"maxDepth\":1}}"));
        BrowseOutcome outcome = browser(t, limits).browseAll();

        assertTrue(outcome.truncated());
        assertTrue(t.browsed.contains(l1));
        assertFalse(t.browsed.contains(l2), "the depth budget did not stop the descent");
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
