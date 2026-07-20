package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;
import com.mbreissi.edgecommons.facades.EventsFacade;
import com.mbreissi.edgecommons.facades.Severity;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * One device instance's southbound command logic, exposed as the UNS {@code cmd/sb/*} verb family
 * (docs/SOUTHBOUND.md §2.2): {@code sb/status}, {@code sb/browse} (hierarchical refs),
 * {@code sb/read}
 * (ref-accepting, regex include/exclude), {@code sb/write} (confirmed, allow-listed batch), and
 * {@code sb/signals} (the configured inventory + writable flag). Each verb method returns the
 * verb-specific <b>result object</b> — the
 * library {@link com.mbreissi.edgecommons.commands.CommandInbox} wraps it as
 * {@code {"ok":true,"result":…}} and replies. A coded failure is a
 * {@link CommandException} ({@code {"ok":false,"error":{code,message}}}).
 *
 * <p>This class no longer subscribes to topics or builds reply envelopes — the component-level
 * {@link CommandRegistry} registers the verbs on the single {@code main}-instance inbox and routes
 * each request to the right device by its {@code instance} field.
 */
public class CommandService {

    private static final Logger LOGGER = LogManager.getLogger(CommandService.class);

    private static final int DEFAULT_TREE_DEPTH = 1;
    private static final int MAX_TREE_DEPTH = 4;
    private static final int DEFAULT_TREE_MAX_REFS = 500;
    private static final int MAX_TREE_MAX_REFS = 2000;

    /** Status marker on an {@code sb/write} entry rejected by {@code writes.allow[]}. */
    private static final String WRITE_NOT_ALLOWED = "write not allowed by writes.allow[]";

    private final OpcUaClient client;
    private final ServerConfiguration serverConfig;
    private final ClientMetrics counters;
    private final BooleanSupplier connected;
    private final Supplier<Map<String, ResolvedSignal>> resolvedSignals;
    private final Map<NodeId, UaVariableNode> allNodes;
    private final EventsFacade events;
    private final HealthState health;

    public CommandService(OpcUaClient client, ServerConfiguration serverConfig, ClientMetrics counters,
                          BooleanSupplier connected, Supplier<Map<String, ResolvedSignal>> resolvedSignals,
                          Map<NodeId, UaVariableNode> allNodes, EventsFacade events, HealthState health) {
        this.client = client;
        this.serverConfig = serverConfig;
        this.counters = counters;
        this.connected = connected;
        this.resolvedSignals = resolvedSignals;
        this.allNodes = allNodes;
        this.events = events;
        this.health = health;
    }

    // ---- sb/status -------------------------------------------------------------------------------

    /** {@code sb/status}: instance/connection status + counters. Result {@code {id, connected, metrics}}. */
    public JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("id", serverConfig.getId());
        result.addProperty("connected", connected.getAsBoolean());
        result.add("metrics", counters.toJsonObject());
        return result;
    }

    // ---- sb/signals ------------------------------------------------------------------------------

    /**
     * {@code sb/signals}: the configured signal inventory — the currently resolved/subscribed signals,
     * each carrying its {@code writable} flag (whether the stable {@code signal.id} is in
     * {@code writes.allow[]}). No device round-trip. Result {@code {id, signals}}.
     */
    public JsonObject signals() {
        JsonObject result = new JsonObject();
        result.addProperty("id", serverConfig.getId());
        JsonArray signals = new JsonArray();
        resolvedSignals.get().forEach((signalId, rt) -> {
            JsonObject t = new JsonObject();
            t.addProperty("signalId", signalId);
            t.addProperty("idType", ValueCodec.idTypeName(rt.nodeId()));
            int idx = rt.nodeId().getNamespaceIndex().intValue();
            t.addProperty("namespace", idx);
            String uri = client.getNamespaceTable().get(idx);
            if (uri != null) {
                t.addProperty("namespaceUri", uri);
            }
            t.addProperty("match", rt.spec().getMatch());
            // The stable signal.id (parseable ns=…;…=… form, as published) is what writes.allow[] matches.
            t.addProperty("writable", serverConfig.isWriteAllowed(rt.nodeId().toParseableString()));
            signals.add(t);
        });
        result.add("signals", signals);
        return result;
    }

    // ---- sb/browse -------------------------------------------------------------------------------

    /**
     * {@code sb/browse}: hierarchical browse refs. Request body
     * {@code {ref?, depth?, maxRefs?}} returns {@code {id, mode:"hierarchical", root}} where each node
     * carries its outgoing hierarchical {@code refs}.
     */
    public JsonObject browse(JsonObject reqBody) throws CommandException {
        counters.recordBrowseRequest();
        try {
            JsonObject result = browseHierarchical(reqBody);
            counters.recordBrowseResult(result.get("refCount").getAsLong(), result.get("truncated").getAsBoolean());
            return result;
        } catch (CommandException | RuntimeException e) {
            counters.recordBrowseFailure();
            throw e;
        }
    }

    private JsonObject browseHierarchical(JsonObject reqBody) throws CommandException {
        NodeId rootId = browseRoot(reqBody);
        int depth = CommandCodec.boundedInt(reqBody, "depth", DEFAULT_TREE_DEPTH, 0, MAX_TREE_DEPTH);
        int maxRefs = CommandCodec.boundedInt(reqBody, "maxRefs", DEFAULT_TREE_MAX_REFS, 1, MAX_TREE_MAX_REFS);
        BrowseBudget budget = new BrowseBudget(maxRefs);

        JsonObject root = nodeObject(rootId);
        addHierarchicalRefs(root, rootId, depth, budget, new HashSet<>());

        JsonObject result = new JsonObject();
        result.addProperty("id", serverConfig.getId());
        result.addProperty("mode", "hierarchical");
        result.addProperty("ref", rootId.toParseableString());
        result.addProperty("depth", depth);
        result.addProperty("maxRefs", maxRefs);
        result.addProperty("refCount", budget.emitted);
        result.addProperty("truncated", budget.truncated);
        result.add("root", root);
        return result;
    }

    private NodeId browseRoot(JsonObject reqBody) throws CommandException {
        if (reqBody == null || !reqBody.has("ref") || reqBody.get("ref").isJsonNull()) {
            return NodeIds.RootFolder;
        }
        JsonElement ref = reqBody.get("ref");
        if (ref.isJsonObject()) {
            // The object-form ref needs the live namespace table (nodeIdFrom) — resolved here, not in
            // the client-free CommandCodec.
            try {
                return nodeIdFrom(ref.getAsJsonObject());
            } catch (Exception e) {
                throw new CommandException(CommandRouter.ERR_BAD_ARGS, "invalid browse ref object: " + e.getMessage());
            }
        }
        return CommandCodec.browseRootRef(ref.getAsString());
    }

    private void addHierarchicalRefs(JsonObject node, NodeId nodeId, int remainingDepth,
                                     BrowseBudget budget, Set<NodeId> path) {
        if (remainingDepth <= 0 || budget.exhausted()) {
            return;
        }
        if (!path.add(nodeId)) {
            node.addProperty("cycle", true);
            return;
        }
        JsonArray refs = new JsonArray();
        try {
            BrowseDescription browse = new BrowseDescription(
                    nodeId,
                    BrowseDirection.Forward,
                    NodeIds.HierarchicalReferences,
                    true,
                    uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue() | NodeClass.Method.getValue()),
                    uint(BrowseResultMask.All.getValue()));
            BrowseResult result = client.browse(browse);
            ReferenceDescription[] references = result.getReferences();
            if (references != null) {
                for (ReferenceDescription rd : references) {
                    if (budget.exhausted()) {
                        break;
                    }
                    JsonObject ref = referenceObject(rd);
                    refs.add(ref);
                    budget.take();
                    rd.getNodeId().toNodeId(client.getNamespaceTable()).ifPresent(targetId -> {
                        JsonObject target = nodeObject(targetId, rd.getNodeClass(), rd.getBrowseName(), rd.getDisplayName());
                        if (remainingDepth > 1 && !budget.exhausted()) {
                            addHierarchicalRefs(target, targetId, remainingDepth - 1, budget, new HashSet<>(path));
                        }
                        ref.add("target", target);
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.trace("[{}] hierarchical browse of {} failed: {}", serverConfig.getId(), nodeId, e.toString());
            node.addProperty("browseError", e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            path.remove(nodeId);
        }
        node.add("refs", refs);
        if (budget.truncated) {
            node.addProperty("truncated", true);
        }
    }

    private JsonObject referenceObject(ReferenceDescription rd) {
        JsonObject ref = new JsonObject();
        NodeId referenceTypeId = rd.getReferenceTypeId();
        if (referenceTypeId != null && referenceTypeId.isNotNull()) {
            ref.addProperty("referenceTypeId", referenceTypeId.toParseableString());
            ref.addProperty("referenceType", referenceTypeName(referenceTypeId));
        }
        if (rd.getIsForward() != null) {
            ref.addProperty("isForward", rd.getIsForward());
        }
        if (rd.getNodeId() != null && rd.getNodeId().isNotNull()) {
            ref.addProperty("targetNodeId", rd.getNodeId().toParseableString());
        }
        if (rd.getTypeDefinition() != null && rd.getTypeDefinition().isNotNull()) {
            ref.addProperty("typeDefinition", rd.getTypeDefinition().toParseableString());
        }
        return ref;
    }

    private String referenceTypeName(NodeId referenceTypeId) {
        try {
            UaNode refTypeNode = client.getAddressSpace().getNode(referenceTypeId);
            QualifiedName browseName = refTypeNode.getBrowseName();
            if (browseName != null && browseName.getName() != null) {
                return browseName.getName();
            }
        } catch (Exception ignore) {
            // The parseable id still gives callers a stable reference type when lookup is denied.
        }
        return referenceTypeId.toParseableString();
    }

    private JsonObject nodeObject(NodeId id) {
        try {
            UaNode node = client.getAddressSpace().getNode(id);
            return nodeObject(id, node.getNodeClass(), node.getBrowseName(), node.getDisplayName());
        } catch (Exception e) {
            JsonObject ret = nodeObject(id, null, null, null);
            if (NodeIds.RootFolder.equals(id)) {
                ret.addProperty("name", "Root");
                ret.addProperty("browseName", "Root");
                ret.addProperty("nodeClass", NodeClass.Object.name());
            }
            return ret;
        }
    }

    private JsonObject nodeObject(NodeId id, NodeClass nodeClass, QualifiedName browseName, LocalizedText displayName) {
        int ns = id.getNamespaceIndex().intValue();
        String uri = client.getNamespaceTable().get(ns);
        String browse = browseName != null && browseName.getName() != null ? browseName.getName() : null;
        String display = displayName != null && displayName.getText() != null ? displayName.getText() : null;
        String name = display != null ? display : browse;
        String dataType = dataTypeName(id, nodeClass);
        JsonObject ret = CommandJson.nodeEntry(ns, uri, id.getIdentifier().toString(),
                ValueCodec.idTypeName(id), name, dataType);
        ret.addProperty("nodeId", id.toParseableString());
        if (browse != null) {
            ret.addProperty("browseName", browse);
        }
        if (nodeClass != null) {
            ret.addProperty("nodeClass", nodeClass.name());
        }
        return ret;
    }

    private String dataTypeName(NodeId id, NodeClass nodeClass) {
        if (nodeClass != NodeClass.Variable) {
            return null;
        }
        try {
            UaVariableNode variable = allNodes.get(id);
            if (variable == null) {
                UaNode node = client.getAddressSpace().getNode(id);
                if (node instanceof UaVariableNode) {
                    variable = (UaVariableNode) node;
                }
            }
            if (variable == null) {
                return null;
            }
            NodeId dt = variable.getDataType();
            if (dt == null) {
                return null;
            }
            OpcUaDataType builtin = OpcUaDataType.fromNodeId(dt);
            return builtin != null ? builtin.name() : dt.toParseableString();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static final class BrowseBudget {
        private final int maxRefs;
        private int emitted;
        private boolean truncated;

        BrowseBudget(int maxRefs) {
            this.maxRefs = maxRefs;
        }

        boolean exhausted() {
            if (emitted >= maxRefs) {
                truncated = true;
                return true;
            }
            return false;
        }

        void take() {
            emitted++;
        }
    }

    // ---- sb/read ---------------------------------------------------------------------------------

    /**
     * {@code sb/read}: on-demand batch read. Request body
     * {@code {"signals":[{namespaceUri|ns, signalId}, ...], "include":[{matcher}], "exclude":[{matcher}]}};
     * result {@code {id, reads:[{signal:{id,address}, value, quality, qualityRaw, sourceTs, serverTs}]}}.
     * {@code signals} names exact signals (order and duplicates preserved); {@code include} (with optional
     * {@code exclude}) selects signals by the same namespace + regex matching used for subscriptions.
     * A malformed matcher throws {@code BAD_ARGS}. The read round-trip is recorded into
     * {@code southbound_health.pollLatencyMs}.
     */
    public JsonObject read(JsonObject payload) throws Exception {
        long started = System.nanoTime();
        try {
            JsonArray signals = (payload != null && payload.has("signals")) ? payload.getAsJsonArray("signals") : new JsonArray();

            List<NodeId> explicit = new ArrayList<>();
            for (JsonElement el : signals) {
                try {
                    explicit.add(nodeIdFrom(el.getAsJsonObject()));
                } catch (Exception e) {
                    LOGGER.warn("[{}] read signal skipped: {}", serverConfig.getId(), e.getMessage());
                }
            }

            List<NodeId> includeMatched = new ArrayList<>();
            if (payload != null && payload.has("include")) {
                List<SignalSpec> includeSpecs;
                List<SignalSpec> excludeSpecs;
                try {
                    includeSpecs = CommandCodec.specsFromJson(payload.getAsJsonArray("include"));
                    excludeSpecs = payload.has("exclude")
                            ? CommandCodec.specsFromJson(payload.getAsJsonArray("exclude"))
                            : List.of();
                    // Compile the caller-supplied regexes up front so a malformed pattern (or a non-object
                    // matcher entry) becomes a coded error reply instead of escaping to a HANDLER_ERROR.
                    includeSpecs.forEach(SignalSpec::pattern);
                    excludeSpecs.forEach(SignalSpec::pattern);
                } catch (Exception e) {
                    LOGGER.warn("[{}] read include/exclude rejected: {}", serverConfig.getId(), e.toString());
                    throw new CommandException(CommandRouter.ERR_BAD_ARGS, "invalid include/exclude: " + e.getMessage());
                }
                NamespaceTable table = client.getNamespaceTable();
                Map<SignalSpec, Integer> includeNs = new HashMap<>();
                includeSpecs.forEach(spec -> includeNs.put(spec, SignalMatching.resolveNamespace(spec, table)));
                Map<SignalSpec, Integer> excludeNs = new HashMap<>();
                excludeSpecs.forEach(spec -> excludeNs.put(spec, SignalMatching.resolveNamespace(spec, table)));

                for (UaVariableNode node : allNodes.values()) {
                    String idStr = node.getNodeId().getIdentifier().toString();
                    String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                            ? node.getBrowseName().getName() : "";
                    String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                            ? node.getDisplayName().getText() : "";
                    int nodeNs = node.getNodeId().getNamespaceIndex().intValue();

                    SignalSpec included = SignalMatching.firstMatch(includeSpecs, includeNs, idStr, browseName, displayName, nodeNs, true);
                    if (included == null) {
                        continue;
                    }
                    if (!excludeSpecs.isEmpty()
                            && SignalMatching.firstMatch(excludeSpecs, excludeNs, idStr, browseName, displayName, nodeNs, false) != null) {
                        continue;
                    }
                    includeMatched.add(node.getNodeId());
                }
            }

            List<NodeId> nodeIds = CommandCodec.mergeReadTargets(explicit, includeMatched);
            List<DataValue> values = nodeIds.isEmpty()
                    ? new ArrayList<>()
                    : client.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get();
            // Record the read round-trip for southbound_health.pollLatencyMs (this subscribe-model
            // adapter's "poll" is an explicit read via sb/read / repoll).
            if (health != null && !nodeIds.isEmpty()) {
                health.setPollLatencyMs(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
            }

            JsonArray reads = new JsonArray();
            for (int i = 0; i < nodeIds.size(); i++) {
                NodeId nodeId = nodeIds.get(i);
                JsonObject signal = new JsonObject();
                signal.addProperty("id", nodeId.toParseableString());
                signal.add("address", ValueCodec.address(nodeId, client.getNamespaceTable()));
                JsonObject read = ValueCodec.toSample(values.get(i));
                read.add("signal", signal);
                reads.add(read);
                counters.recordReadRequest();
            }
            JsonObject result = new JsonObject();
            result.addProperty("id", serverConfig.getId());
            result.add("reads", reads);
            return result;
        } catch (Exception e) {
            counters.recordReadFailure();
            counters.incrementReadErrors();
            throw e;
        }
    }

    // ---- sb/write --------------------------------------------------------------------------------

    /**
     * {@code sb/write}: confirmed, allow-listed batch write. Request body
     * {@code {"writes":[{ns|namespaceUri, signalId, value, status?, sourceTs?}, ...]}} (a single object
     * without the {@code writes} array is also accepted); result {@code {id, writes:[{coords, status,
     * message?, qualityRaw?}]}} — one entry per request entry, in order, each {@code SUCCESS}/{@code
     * FAILED}. A target whose stable {@code signal.id} is not in {@code writes.allow[]} (D‑U16) is
     * rejected per-entry (marked {@code FAILED}, never issued) and raises an
     * {@code evt/warning/write-rejected} — the whole batch is not failed, so allowed writes still
     * proceed.
     */
    public JsonObject write(JsonObject payload) {
        JsonArray writes;
        if (payload != null && payload.has("writes")) {
            writes = payload.getAsJsonArray("writes");
        } else {
            writes = new JsonArray();
            if (payload != null) {
                writes.add(payload);
            }
        }

        List<JsonObject> perEntry = new ArrayList<>();
        List<NodeId> nodeIds = new ArrayList<>();
        List<DataValue> dataValues = new ArrayList<>();
        List<Integer> batchToEntry = new ArrayList<>();

        for (JsonElement el : writes) {
            JsonObject w = el.getAsJsonObject();
            String signalId = w.has("signalId") ? w.get("signalId").getAsString() : null;
            Integer ns = w.has("ns") ? w.get("ns").getAsInt() : null;
            String namespaceUri = w.has("namespaceUri") ? w.get("namespaceUri").getAsString() : null;
            JsonObject coords = CommandJson.writeRequested(signalId, ns, namespaceUri);
            perEntry.add(coords);

            if (!w.has("value")) {
                LOGGER.error("[{}] write entry missing value; skipping: {}", serverConfig.getId(), w);
                markWriteFailed(coords, "missing value");
                continue;
            }
            NodeId nodeId;
            try {
                nodeId = nodeIdFrom(w);
            } catch (Exception e) {
                LOGGER.error("[{}] write entry skipped: {}", serverConfig.getId(), e.getMessage());
                markWriteFailed(coords, e.getMessage());
                continue;
            }
            // D-U16 allow-list: match the target's stable signal.id (the parseable ns=…;…=… form, as
            // published) against writes.allow[]. A rejected target is confirmed FAILED and surfaced as
            // an operator event, but does not fail the sibling (allowed) writes in the batch.
            String stableId = nodeId.toParseableString();
            if (!serverConfig.isWriteAllowed(stableId)) {
                LOGGER.warn("[{}] write to {} rejected: not in writes.allow[]", serverConfig.getId(), stableId);
                markWriteFailed(coords, WRITE_NOT_ALLOWED);
                emitWriteRejected(stableId);
                continue;
            }
            UaNode node;
            try {
                node = client.getAddressSpace().getNode(nodeId);
            } catch (Exception e) {
                LOGGER.error("[{}] write target {} could not be resolved: {}", serverConfig.getId(), nodeId, e.getMessage());
                markWriteFailed(coords, "node not found: " + e.getMessage());
                continue;
            }
            if (!(node instanceof UaVariableNode)) {
                LOGGER.error("[{}] write target {} is not a variable; skipping", serverConfig.getId(), nodeId);
                markWriteFailed(coords, "target is not a variable");
                continue;
            }
            Variant variant = ValueCodec.variantFromValue(((UaVariableNode) node).getDataType(), w.get("value"));
            if (variant == null) {
                markWriteFailed(coords, "unsupported value type or coercion failed");
                continue;
            }
            StatusCode statusCode = ValueCodec.statusFromString(w.has("status") ? w.get("status").getAsString() : "GOOD");
            DataValue dv = new DataValue(variant, statusCode, ValueCodec.sourceTime(w.has("sourceTs") ? w.get("sourceTs").getAsString() : null));
            nodeIds.add(nodeId);
            dataValues.add(dv);
            batchToEntry.add(perEntry.size() - 1);
        }

        if (!nodeIds.isEmpty()) {
            try {
                List<StatusCode> results = client.writeValues(nodeIds, dataValues);
                for (int j = 0; j < results.size(); j++) {
                    counters.recordWriteRequest();
                    StatusCode result = results.get(j);
                    if (result.isBad()) {
                        counters.recordWriteFailure();
                        counters.incrementWriteErrors();
                        LOGGER.warn("[{}] write to {} returned {}", serverConfig.getId(), nodeIds.get(j), result);
                    }
                }
                CommandJson.applyBatchResults(perEntry, batchToEntry, results);
                LOGGER.debug("[{}] batch write of {} signal(s) complete", serverConfig.getId(), nodeIds.size());
            } catch (Exception e) {
                // The write service call itself failed (e.g. session down): mark every batched entry
                // FAILED so the reply still carries a result for each one.
                LOGGER.error("[{}] batch write failed: {}", serverConfig.getId(), e.toString());
                String message = "write failed: " + (e.getMessage() != null ? e.getMessage() : e.toString());
                for (int entryIdx : batchToEntry) {
                    markWriteFailed(perEntry.get(entryIdx), message);
                }
            }
        }

        JsonArray writesResult = new JsonArray();
        perEntry.forEach(writesResult::add);
        JsonObject result = new JsonObject();
        result.addProperty("id", serverConfig.getId());
        result.add("writes", writesResult);
        return result;
    }

    private void markWriteFailed(JsonObject entry, String message) {
        CommandJson.applyWriteFailed(entry, message);
        counters.recordWriteFailure();
        counters.incrementWriteErrors();
    }

    private void emitWriteRejected(String stableSignalId) {
        if (events == null) {
            return;
        }
        JsonObject context = new JsonObject();
        context.addProperty("id", serverConfig.getId());
        context.addProperty("signalId", stableSignalId);
        // severity=warning + type="write-rejected" -> the facade derives evt/warning/write-rejected
        // (same channel as before the migration; now non-negotiable, not hand-assembled).
        events.emit(Severity.WARNING, "write-rejected", "not in writes.allow[]", context);
    }


    /**
     * Build a {@link NodeId} from a read/write request entry. The namespace is identified by
     * {@code namespaceUri} (preferred — resolved to the server's current index) or a literal {@code ns}
     * index; {@code signalId} is the node identifier and the optional {@code idType}
     * ({@code Numeric}/{@code String}/{@code Guid}; default {@code String}) selects its OPC UA
     * identifier type so numeric/GUID nodes round-trip correctly. Throws if the entry is incomplete or
     * the URI is not in the server's namespace table.
     */
    private NodeId nodeIdFrom(JsonObject o) {
        if (!o.has("signalId")) {
            throw new IllegalArgumentException("entry missing 'signalId': " + o);
        }
        int ns;
        if (o.has("namespaceUri")) {
            String uri = o.get("namespaceUri").getAsString();
            var idx = client.getNamespaceTable().getIndex(uri);
            if (idx == null) {
                throw new IllegalArgumentException("namespaceUri '" + uri + "' not in the server's namespace table");
            }
            ns = idx.intValue();
        } else if (o.has("ns")) {
            ns = o.get("ns").getAsInt();
        } else {
            throw new IllegalArgumentException("entry needs 'namespaceUri' or 'ns': " + o);
        }
        String idType = o.has("idType") ? o.get("idType").getAsString() : null;
        return ValueCodec.nodeId(ns, o.get("signalId").getAsString(), idType);
    }
}
