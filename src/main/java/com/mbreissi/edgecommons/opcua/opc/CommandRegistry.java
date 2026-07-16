package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;
import com.mbreissi.edgecommons.commands.CommandInbox;
import com.mbreissi.edgecommons.messaging.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The component-level southbound command surface: registers the {@code cmd/sb/*} verb family
 * (docs/SOUTHBOUND.md §2.2) <b>once</b> on the library's single component-scope command inbox
 * ({@code ecv1/{device}/{component}/cmd/#}) and routes each request to the right
 * {@link OpcUaDevice} by its {@code instance} field.
 *
 * <p><b>Why component-level.</b> The shipped {@link CommandInbox} is one-per-component (per-instance
 * inboxes are Phase 5), while this adapter is multi-instance (one {@link OpcUaDevice} per
 * {@code component.instances[]} entry). So a single set of verbs is registered here and each request
 * body carries an {@code "instance"} selector; when exactly one instance is configured the selector
 * may be omitted. Devices register themselves via {@link #addDevice(OpcUaDevice)} as their connections
 * come up, so a verb aimed at a not-yet-connected instance replies with {@code UNKNOWN_INSTANCE}
 * rather than blocking.
 *
 * <p>Verbs (all request/reply): {@code sb/status}, {@code sb/browse} (hierarchical refs),
 * {@code sb/read}
 * (ref-accepting, regex include/exclude), {@code sb/write} (confirmed, allow-listed batch),
 * {@code sb/subscriptions}, and {@code sb/rescan} (re-browse the address space).
 */
public class CommandRegistry {

    private static final Logger LOGGER = LogManager.getLogger(CommandRegistry.class);

    /** Error code: the request named (or defaulted to) an instance that is not connected. */
    public static final String ERR_UNKNOWN_INSTANCE = "UNKNOWN_INSTANCE";

    /** Error code: several instances are configured but the request omitted the {@code instance} selector. */
    public static final String ERR_INSTANCE_REQUIRED = "INSTANCE_REQUIRED";

    private final CommandInbox commands;
    private final Map<String, OpcUaDevice> devices = new ConcurrentHashMap<>();

    public CommandRegistry(CommandInbox commands) {
        this.commands = commands;
    }

    /** Registers a connected device instance so its verbs become routable. */
    public void addDevice(OpcUaDevice device) {
        devices.put(device.getId(), device);
    }

    /**
     * Registers the {@code sb/*} verbs on the command inbox. Idempotent per verb — the inbox rejects a
     * duplicate registration. No-op when the inbox is absent (a mock/test bring-up with no resolved
     * identity), so the adapter still comes up.
     */
    public void registerVerbs() {
        if (commands == null) {
            LOGGER.warn("No command inbox available - the sb/* command surface is disabled");
            return;
        }
        commands.register("sb/status", req -> route(req).commandService().status());
        commands.register("sb/browse", req -> route(req).commandService().browse(req));
        commands.register("sb/read", req -> route(req).commandService().read(req));
        commands.register("sb/write", req -> route(req).commandService().write(req));
        commands.register("sb/subscriptions", req -> route(req).commandService().subscriptions());
        commands.register("sb/rescan", req -> route(req).rescan());
        registerPanels();
        LOGGER.info("Registered southbound command verbs: {}", commands.verbs());
    }

    /** Registers the descriptor-driven component panels exposed by {@code cmd/describe}. */
    private void registerPanels() {
        commands.registerPanel(panel("overview", "Overview", 10,
                summaryWidget("opcua-summary", "OPC UA adapter",
                        row("Address space", "Hierarchical browse via cmd/sb/browse"),
                        row("Reads", "Explicit node reads and configured-signal matching"),
                        row("Diagnostics", "Status, subscriptions, and rescan commands")),
                commandSummaryWidget("opcua-command-bindings", "Command bindings",
                        "sb/status", "sb/browse", "sb/read", "sb/write", "sb/subscriptions", "sb/rescan")));
        commands.registerPanel(panel("address-space", "Address Space", 20,
                treeBrowserWidget("address-space-tree", "Address space",
                        "sb/browse", "sb/read", "sb/write")));
        commands.registerPanel(panel("signals", "Signals", 30,
                signalGridWidget("configured-signals", "Configured signals", "sb/subscriptions", "sb/read")));
        commands.registerPanel(panel("diagnostics", "Diagnostics", 40,
                commandSummaryWidget("diagnostic-commands", "Diagnostic commands",
                        "sb/status", "sb/subscriptions", "sb/rescan"),
                summaryWidget("diagnostic-notes", "Diagnostics",
                        row("Status", "Live southbound session and address-space counters"),
                        row("Subscriptions", "Configured signal bindings by instance"),
                        row("Rescan", "Rebuild the discovered address-space cache"))));
    }

    private static JsonObject panel(String id, String title, int order, JsonObject... widgets) {
        JsonObject panel = new JsonObject();
        panel.addProperty("id", id);
        panel.addProperty("title", title);
        panel.addProperty("order", order);
        JsonArray widgetArray = new JsonArray();
        for (JsonObject widget : widgets) {
            widgetArray.add(widget);
        }
        panel.add("widgets", widgetArray);
        return panel;
    }

    private static JsonObject summaryWidget(String id, String title, JsonObject... rows) {
        JsonObject widget = baseWidget("summary", id, title);
        JsonArray rowArray = new JsonArray();
        for (JsonObject row : rows) {
            rowArray.add(row);
        }
        widget.add("rows", rowArray);
        return widget;
    }

    private static JsonObject commandSummaryWidget(String id, String title, String... verbs) {
        JsonObject widget = baseWidget("commandSummary", id, title);
        JsonArray verbArray = new JsonArray();
        for (String verb : verbs) {
            verbArray.add(verb);
        }
        widget.add("verbs", verbArray);
        return widget;
    }

    private static JsonObject treeBrowserWidget(String id, String title,
                                                String browseVerb, String readVerb,
                                                String writeVerb) {
        JsonObject widget = baseWidget("treeBrowser", id, title);
        widget.addProperty("scope", "instance");
        widget.addProperty("mode", "hierarchical");
        widget.addProperty("rootRef", "root");
        widget.addProperty("browseVerb", browseVerb);
        widget.addProperty("readVerb", readVerb);
        widget.addProperty("writeVerb", writeVerb);
        return widget;
    }

    private static JsonObject signalGridWidget(String id, String title,
                                               String subscriptionsVerb, String readVerb) {
        JsonObject widget = baseWidget("signalGrid", id, title);
        widget.addProperty("scope", "instance");
        widget.addProperty("subscriptionsVerb", subscriptionsVerb);
        widget.addProperty("readVerb", readVerb);
        return widget;
    }

    private static JsonObject baseWidget(String kind, String id, String title) {
        JsonObject widget = new JsonObject();
        widget.addProperty("kind", kind);
        widget.addProperty("id", id);
        widget.addProperty("title", title);
        return widget;
    }

    private static JsonObject row(String label, String value) {
        JsonObject row = new JsonObject();
        row.addProperty("label", label);
        row.addProperty("value", value);
        return row;
    }

    /**
     * Resolves the target device for a request: the request body's {@code "instance"} field, or — when
     * exactly one instance is connected — that sole instance.
     *
     * @throws CommandException {@code INSTANCE_REQUIRED} (multiple instances, no selector) or
     *                          {@code UNKNOWN_INSTANCE} (named/sole instance not connected)
     */
    private OpcUaDevice route(Message request) throws CommandException {
        String instance = instanceOf(request);
        if (instance == null) {
            if (devices.size() == 1) {
                return devices.values().iterator().next();
            }
            if (devices.isEmpty()) {
                throw new CommandException(ERR_UNKNOWN_INSTANCE, "no device instance is connected yet");
            }
            throw new CommandException(ERR_INSTANCE_REQUIRED, "several instances are connected ("
                    + devices.keySet() + "); include an \"instance\" field in the request body");
        }
        OpcUaDevice device = devices.get(instance);
        if (device == null) {
            throw new CommandException(ERR_UNKNOWN_INSTANCE, "instance '" + instance
                    + "' is not connected (connected: " + devices.keySet() + ")");
        }
        return device;
    }

    private static String instanceOf(Message request) {
        if (request == null) {
            return null;
        }
        Object body = request.getBody();
        JsonObject obj = body instanceof JsonObject ? (JsonObject) body
                : (request.getRaw() instanceof JsonObject ? (JsonObject) request.getRaw() : null);
        if (obj != null && obj.has("instance") && !obj.get("instance").isJsonNull()) {
            return obj.get("instance").getAsString();
        }
        return null;
    }
}
