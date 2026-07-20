package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandInbox;
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
     * Registers the {@code sb/*} verbs on the command inbox. Idempotent per verb — the inbox rejects a
     * duplicate registration. No-op when the inbox is absent (a mock/test bring-up with no resolved
     * identity), so the adapter still comes up.
     */
    public void registerVerbs() {
        if (commands == null) {
            LOGGER.warn("No command inbox available - the sb/* command surface is disabled");
            return;
        }
        commands.register("sb/status", req -> router.status(CommandCodec.bodyOf(req)));
        commands.register("sb/browse", req -> router.browse(CommandCodec.bodyOf(req)));
        commands.register("sb/read", req -> router.read(CommandCodec.bodyOf(req)));
        commands.register("sb/write", req -> router.write(CommandCodec.bodyOf(req)));
        commands.register("sb/signals", req -> router.signals(CommandCodec.bodyOf(req)));
        commands.register("sb/rescan", req -> router.rescan(CommandCodec.bodyOf(req)));
        commands.register("sb/pause", req -> router.pause(CommandCodec.bodyOf(req)));
        commands.register("sb/resume", req -> router.resume(CommandCodec.bodyOf(req)));
        commands.register("reconnect", req -> router.reconnect(CommandCodec.bodyOf(req)));
        commands.register("repoll", req -> router.repoll(CommandCodec.bodyOf(req)));
        for (JsonObject panel : Panels.all()) {
            commands.registerPanel(panel);
        }
        LOGGER.info("Registered southbound command verbs: {}", commands.verbs());
    }
}
