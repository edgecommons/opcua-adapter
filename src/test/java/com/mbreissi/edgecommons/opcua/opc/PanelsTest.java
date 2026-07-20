package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the edge-console panel descriptors: the four-panel trio, each {@code scope: "instance"}, the
 * lifecycle-verb bindings on the {@code overview} panel, and the {@code signals} panel's verb wiring —
 * including the {@code subscriptionsVerb} compatibility hint pointing at {@code sb/signals} that keeps
 * the shipped edge-console signalGrid working after the {@code sb/subscriptions} → {@code sb/signals}
 * rename.
 */
class PanelsTest {

    private static JsonObject panel(String id) {
        for (JsonObject p : Panels.all()) {
            if (id.equals(p.get("id").getAsString())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    private static JsonObject widgetOfKind(JsonObject panel, String kind) {
        for (var el : panel.getAsJsonArray("widgets")) {
            JsonObject w = el.getAsJsonObject();
            if (kind.equals(w.get("kind").getAsString())) {
                return w;
            }
        }
        throw new AssertionError("no " + kind + " widget in panel " + panel.get("id").getAsString());
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    void all_hasTheFourPanelsWithInstanceScopeAndOrders() {
        List<JsonObject> panels = Panels.all();
        assertEquals(4, panels.size());
        assertEquals(List.of("overview", "address-space", "signals", "diagnostics"),
                panels.stream().map(p -> p.get("id").getAsString()).toList());
        assertEquals(List.of(10, 20, 30, 40),
                panels.stream().map(p -> p.get("order").getAsInt()).toList());
        panels.forEach(p -> assertEquals("instance", p.get("scope").getAsString()));
    }

    @Test
    void overview_bindsTheLifecycleControlVerbs() {
        JsonObject cmd = widgetOfKind(panel("overview"), "commandSummary");
        List<String> verbs = strings(cmd.getAsJsonArray("verbs"));
        assertTrue(verbs.containsAll(List.of("reconnect", "sb/pause", "sb/resume", "repoll")),
                "overview must bind the lifecycle verbs, was " + verbs);
    }

    @Test
    void signals_signalGrid_usesSbSignals_andKeepsSubscriptionsVerbCompatHint() {
        JsonObject grid = widgetOfKind(panel("signals"), "signalGrid");
        assertEquals("sb/signals", grid.get("signalsVerb").getAsString());
        // Compat hint for the shipped edge-console (reads subscriptionsVerb) — points at the new verb.
        assertEquals("sb/signals", grid.get("subscriptionsVerb").getAsString());
        assertEquals("instance", grid.get("scope").getAsString());
    }

    @Test
    void addressSpace_treeBrowser_bindsBrowseReadWrite() {
        JsonObject tree = widgetOfKind(panel("address-space"), "treeBrowser");
        assertEquals("sb/browse", tree.get("browseVerb").getAsString());
        assertEquals("sb/read", tree.get("readVerb").getAsString());
        assertEquals("sb/write", tree.get("writeVerb").getAsString());
        assertNotNull(tree.get("rootRef"));
    }
}
