package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandInbox;
import com.mbreissi.edgecommons.messaging.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The component-level southbound command surface: registers the {@code cmd/sb/*} verb family
 * (docs/SOUTHBOUND.md §2.2) <b>once</b> on the library's single component-scope command inbox
 * ({@code ecv1/{device}/{component}/cmd/#}) and routes each request to the right {@link OpcUaDevice} by
 * its {@code instance} field via the pure {@link CommandRouter}.
 *
 * <p><b>Why component-level.</b> The shipped {@link CommandInbox} is one-per-component, while this
 * adapter is multi-instance (one {@link OpcUaDevice} per {@code component.instances[]} entry). So a
 * single set of verbs is registered here and each request body carries an {@code "instance"} selector;
 * when exactly one instance is connected the selector may be omitted. Devices register themselves via
 * {@link #addDevice(OpcUaDevice)} as their connections come up, so a verb aimed at a not-yet-connected
 * instance replies with the standardized {@code NO_SUCH_INSTANCE} rather than blocking.
 *
 * <p>Verbs (all request/reply): {@code sb/status}, {@code sb/browse} (paged, hierarchical refs),
 * {@code sb/read} (ref-accepting, regex include/exclude), {@code sb/write} (confirmed, allow-listed
 * batch), {@code sb/signals} (configured inventory + writable flag), {@code sb/rescan} (re-browse the
 * address space), and the standardized lifecycle-control family {@code sb/pause} / {@code sb/resume} /
 * {@code reconnect} / {@code repoll}.
 */
public class CommandRegistry {

    private static final Logger LOGGER = LogManager.getLogger(CommandRegistry.class);

    private final CommandInbox commands;
    private final CommandRouter router = new CommandRouter();

    public CommandRegistry(CommandInbox commands) {
        this.commands = commands;
    }

    /** Registers a connected device instance so its verbs become routable. */
    public void addDevice(OpcUaDevice device) {
        router.addDevice(device);
    }

    /** The pure dispatcher, exposed for wiring/tests. */
    CommandRouter router() {
        return router;
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
        commands.register("sb/status", req -> router.status(bodyOf(req)));
        commands.register("sb/browse", req -> router.browse(bodyOf(req)));
        commands.register("sb/read", req -> router.read(bodyOf(req)));
        commands.register("sb/write", req -> router.write(bodyOf(req)));
        commands.register("sb/signals", req -> router.signals(bodyOf(req)));
        commands.register("sb/rescan", req -> router.rescan(bodyOf(req)));
        commands.register("sb/pause", req -> router.pause(bodyOf(req)));
        commands.register("sb/resume", req -> router.resume(bodyOf(req)));
        commands.register("reconnect", req -> router.reconnect(bodyOf(req)));
        commands.register("repoll", req -> router.repoll(bodyOf(req)));
        registerPanels();
        LOGGER.info("Registered southbound command verbs: {}", commands.verbs());
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

    /** Registers the descriptor-driven component panels exposed by {@code cmd/describe}. */
    private void registerPanels() {
        commands.registerPanel(panel("overview", "Overview", 10,
                summaryWidget("opcua-summary", "OPC UA adapter",
                        row("Address space", "Hierarchical browse via cmd/sb/browse"),
                        row("Reads", "Explicit node reads and configured-signal matching"),
                        row("Lifecycle", "Pause, resume, reconnect, and repoll the instance")),
                commandSummaryWidget("opcua-lifecycle", "Lifecycle bindings",
                        "sb/status", "reconnect", "sb/pause", "sb/resume", "repoll")));
        commands.registerPanel(panel("address-space", "Address Space", 20,
                treeBrowserWidget("address-space-tree", "Address space",
                        "sb/browse", "sb/read", "sb/write")));
        commands.registerPanel(panel("signals", "Signals", 30,
                signalGridWidget("configured-signals", "Configured signals", "sb/signals", "sb/read")));
        commands.registerPanel(panel("diagnostics", "Diagnostics", 40,
                commandSummaryWidget("diagnostic-commands", "Diagnostic commands",
                        "sb/status", "sb/signals", "sb/rescan", "reconnect"),
                summaryWidget("diagnostic-notes", "Diagnostics",
                        row("Status", "Live southbound session and address-space counters"),
                        row("Signals", "Configured signal inventory by instance"),
                        row("Rescan", "Rebuild the discovered address-space cache"))));
    }

    private static JsonObject panel(String id, String title, int order, JsonObject... widgets) {
        JsonObject panel = new JsonObject();
        panel.addProperty("id", id);
        panel.addProperty("title", title);
        panel.addProperty("order", order);
        panel.addProperty("scope", "instance");
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
                                               String signalsVerb, String readVerb) {
        JsonObject widget = baseWidget("signalGrid", id, title);
        widget.addProperty("scope", "instance");
        widget.addProperty("signalsVerb", signalsVerb);
        // Descriptor-compat hint: the shipped edge-console signalGrid reads `subscriptionsVerb`
        // (falling back to the removed `sb/subscriptions`). Point that key at the new `sb/signals`
        // verb too, so the current console binds correctly until it reads `signalsVerb`. This is a
        // descriptor field alias, NOT a wire-verb alias — the `sb/subscriptions` verb itself is gone.
        widget.addProperty("subscriptionsVerb", signalsVerb);
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
}
