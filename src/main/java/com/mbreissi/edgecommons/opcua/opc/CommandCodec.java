package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;
import com.mbreissi.edgecommons.messaging.Message;
import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure, client-free decision logic behind the {@code sb/*} command handlers — the parts of the
 * command surface that need <b>no</b> live OPC UA session, extracted out of {@link CommandService} (the
 * Milo I/O engine) and {@link CommandRegistry} (the inbox wiring) so they are exercised by the
 * in-process unit suite rather than hidden behind the live-driver coverage exclusion.
 *
 * <p>Everything here is a static function over JSON and OPC UA <i>value</i> types (never the Milo
 * client): request-arg clamping, the string {@code sb/browse} root-ref resolution, the
 * explicit/include read-target merge, the request-body extraction, and the signal-spec parse. The
 * client-touching remainder (address-space browse, reads/writes, namespace-table resolution) stays in
 * {@link CommandService}, which is validated against a real server.
 */
public final class CommandCodec {

    private CommandCodec() {
    }

    /** The request body as a {@link JsonObject} (an empty object when the payload is not one). */
    static JsonObject bodyOf(Message request) {
        if (request == null) {
            return new JsonObject();
        }
        Object body = request.getBody();
        if (body instanceof JsonObject jo) {
            return jo;
        }
        Object raw = request.getRaw();
        return raw instanceof JsonObject jo ? jo : new JsonObject();
    }

    /** Read an integer request arg, defaulting when absent/null and clamping to {@code [min, max]}. */
    static int boundedInt(JsonObject reqBody, String key, int defaultValue, int min, int max) {
        if (reqBody == null || !reqBody.has(key) || reqBody.get(key).isJsonNull()) {
            return defaultValue;
        }
        int value = reqBody.get(key).getAsInt();
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Resolve a string {@code sb/browse} root reference to a {@link NodeId}: the well-known roots
     * ({@code root}/blank, {@code objects}, {@code types}, {@code views}) or a parseable OPC UA NodeId.
     * A malformed id is {@code BAD_ARGS}. (The object-form ref — needing the live namespace table — is
     * resolved in {@link CommandService}.)
     */
    static NodeId browseRootRef(String value) throws CommandException {
        if (value == null || value.isBlank() || "root".equalsIgnoreCase(value)) {
            return NodeIds.RootFolder;
        }
        if ("objects".equalsIgnoreCase(value)) {
            return NodeIds.ObjectsFolder;
        }
        if ("types".equalsIgnoreCase(value)) {
            return NodeIds.TypesFolder;
        }
        if ("views".equalsIgnoreCase(value)) {
            return NodeIds.ViewsFolder;
        }
        try {
            return NodeId.parse(value);
        } catch (Exception e) {
            throw new CommandException(CommandRouter.ERR_BAD_ARGS,
                    "invalid browse ref '" + value + "'; use 'root' or a parseable NodeId such as ns=2;s=Channel.Device.Tag");
        }
    }

    /**
     * The ordered read-target list: the {@code explicit} signals as given (order and duplicates
     * preserved), followed by {@code include}-matched node ids not already present (deduplicated against
     * the explicit entries and against each other).
     */
    static List<NodeId> mergeReadTargets(List<NodeId> explicit, List<NodeId> includeMatched) {
        List<NodeId> result = new ArrayList<>(explicit);
        Set<NodeId> seen = new HashSet<>(explicit);
        for (NodeId id : includeMatched) {
            if (seen.add(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** Parse a JSON array of signal-matcher objects into {@link SignalSpec}s. */
    static List<SignalSpec> specsFromJson(JsonArray array) {
        List<SignalSpec> specs = new ArrayList<>();
        for (JsonElement el : array) {
            specs.add(SignalSpec.fromJson(el.getAsJsonObject()));
        }
        return specs;
    }
}
