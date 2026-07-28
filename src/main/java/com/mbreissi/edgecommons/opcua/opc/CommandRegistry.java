package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandInbox;
import com.mbreissi.edgecommons.commands.CommandScope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The component-level southbound command surface: registers the {@code cmd/sb/*} verb family
 * (docs/SOUTHBOUND.md §2.2) <b>once</b> on the library's single per-component command inbox — which
 * subscribes both D‑U28 command scopes ({@code ecv1/{device}/{component}/cmd/#} and
 * {@code ecv1/{device}/{component}/+/cmd/#}) — and routes each request to the right
 * {@link OpcUaDevice} via the pure {@link CommandRouter}.
 *
 * <p><b>Declared verb scope.</b> Every verb here registers
 * {@link CommandScope#INSTANCE} (DESIGN-scoped-commands D‑SC‑2): each one acts on exactly one OPC UA
 * server. The library owns <b>addressing</b> and enforces it before dispatch — it extracts the
 * delivery topic's {@code {instance}} token and the body's {@code instance} field, refuses a conflict
 * between the two with {@code BAD_ARGS}, and hands the handler the resolved {@code addressedInstance}
 * (topic token, else body field, else {@code null}). The two policies that need this adapter's
 * configuration stay here (D‑SC‑4): the optional-iff-one default and {@code NO_SUCH_INSTANCE}, both
 * applied by the {@link CommandRouter}.
 *
 * <p><b>Why component-level.</b> The shipped {@link CommandInbox} is one-per-component, while this
 * adapter is multi-instance (one {@link OpcUaDevice} per {@code component.instances[]} entry), so a
 * single set of verbs is registered here. Devices register themselves via
 * {@link #addDevice(OpcUaDevice)} as their connections come up, so a verb aimed at a not-yet-connected
 * instance replies with the standardized {@code NO_SUCH_INSTANCE} rather than blocking.
 *
 * <p>This class is a thin wiring shell: the dispatch/routing/error-code logic lives in the (tested)
 * {@link CommandRouter}, the panel descriptors in the (tested) {@link Panels}, and the request-body
 * extraction in the (tested) {@link CommandCodec}. Only the register-on-the-live-inbox calls remain
 * here — they cannot run without a live {@link CommandInbox}.
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
     * Registers the {@code sb/*} verbs on the command inbox, each declaring
     * {@link CommandScope#INSTANCE}: the inbox subscribes both D‑U28 command scopes, enforces the
     * declared scope before dispatch, and hands each handler the resolved addressed instance
     * ({@code null} when the request named none — the {@link CommandRouter} then applies the
     * optional-iff-one default). Idempotent per verb — the inbox rejects a duplicate registration.
     * No-op when the inbox is absent (a mock/test bring-up with no resolved identity), so the adapter
     * still comes up.
     */
    public void registerVerbs() {
        if (commands == null) {
            LOGGER.warn("No command inbox available - the sb/* command surface is disabled");
            return;
        }
        commands.register("sb/status", CommandScope.INSTANCE, (req, inst) -> router.status(inst));
        commands.register("sb/browse", CommandScope.INSTANCE,
                (req, inst) -> router.browse(CommandCodec.bodyOf(req), inst));
        commands.register("sb/read", CommandScope.INSTANCE,
                (req, inst) -> router.read(CommandCodec.bodyOf(req), inst));
        commands.register("sb/write", CommandScope.INSTANCE,
                (req, inst) -> router.write(CommandCodec.bodyOf(req), inst));
        commands.register("sb/signals", CommandScope.INSTANCE, (req, inst) -> router.signals(inst));
        commands.register("sb/rescan", CommandScope.INSTANCE, (req, inst) -> router.rescan(inst));
        commands.register("sb/pause", CommandScope.INSTANCE, (req, inst) -> router.pause(inst));
        commands.register("sb/resume", CommandScope.INSTANCE, (req, inst) -> router.resume(inst));
        commands.register("reconnect", CommandScope.INSTANCE, (req, inst) -> router.reconnect(inst));
        commands.register("repoll", CommandScope.INSTANCE, (req, inst) -> router.repoll(inst));
        for (JsonObject panel : Panels.all()) {
            commands.registerPanel(panel);
        }
        LOGGER.info("Registered southbound command verbs: {}", commands.verbs());
    }
}
