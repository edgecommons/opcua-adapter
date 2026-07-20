package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The edge-console panel descriptors for the OPC UA adapter — the {@code overview} / {@code signals} /
 * {@code diagnostics} / {@code address-space} trio (plus one) advertised through {@code cmd/describe}.
 *
 * <p>Pure descriptor construction (JSON only), extracted from {@link CommandRegistry} so the descriptor
 * shape — the lifecycle-verb bindings on the {@code overview} panel and the {@code signals} panel's
 * verb wiring, including the {@code subscriptionsVerb} compatibility hint the shipped edge-console reads
 * — is pinned by a unit test rather than hidden behind the live inbox-wiring seam. {@link CommandRegistry}
 * only iterates {@link #all()} and registers each on the live inbox.
 */
public final class Panels {

    private Panels() {
    }

    /** Every panel descriptor this adapter registers, in order. */
    public static List<JsonObject> all() {
        List<JsonObject> out = new ArrayList<>();
        out.add(panel("overview", "Overview", 10,
                summaryWidget("opcua-summary", "OPC UA adapter",
                        row("Address space", "Hierarchical browse via cmd/sb/browse"),
                        row("Reads", "Explicit node reads and configured-signal matching"),
                        row("Lifecycle", "Pause, resume, reconnect, and repoll the instance")),
                commandSummaryWidget("opcua-lifecycle", "Lifecycle bindings",
                        "sb/status", "reconnect", "sb/pause", "sb/resume", "repoll")));
        out.add(panel("address-space", "Address Space", 20,
                treeBrowserWidget("address-space-tree", "Address space",
                        "sb/browse", "sb/read", "sb/write")));
        out.add(panel("signals", "Signals", 30,
                signalGridWidget("configured-signals", "Configured signals", "sb/signals", "sb/read")));
        out.add(panel("diagnostics", "Diagnostics", 40,
                commandSummaryWidget("diagnostic-commands", "Diagnostic commands",
                        "sb/status", "sb/signals", "sb/rescan", "reconnect"),
                summaryWidget("diagnostic-notes", "Diagnostics",
                        row("Status", "Live southbound session and address-space counters"),
                        row("Signals", "Configured signal inventory by instance"),
                        row("Rescan", "Rebuild the discovered address-space cache"))));
        return out;
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
