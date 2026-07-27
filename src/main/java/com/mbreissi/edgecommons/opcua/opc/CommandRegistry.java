package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandInbox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The component-level southbound command surface: registers the {@code cmd/sb/*} verb family
 * (docs/SOUTHBOUND.md §2.2) <b>once</b> on the library's single per-component command inbox — which
 * subscribes both D‑U28 command scopes ({@code ecv1/{device}/{component}/cmd/#} and
 * {@code ecv1/{device}/{component}/+/cmd/#}) — and routes each request to the right
 * {@link OpcUaDevice} via the pure {@link CommandRouter}: the topic-addressed instance token when the
 * command was instance-scoped (authoritative), else the request's {@code instance} body field.
 *
 * <p><b>Why component-level.</b> The shipped {@link CommandInbox} is one-per-component, while this
 * adapter is multi-instance (one {@link OpcUaDevice} per {@code component.instances[]} entry). So a
 * single set of verbs is registered here through {@link CommandInbox#registerScoped}; a request
 * addressed instance-scope routes by its topic token, a component-scoped request carries an
 * {@code "instance"} body selector (omittable when exactly one instance is connected). Devices
 * register themselves via {@link #addDevice(OpcUaDevice)} as their connections come up, so a verb
 * aimed at a not-yet-connected instance replies with the standardized {@code NO_SUCH_INSTANCE}
 * rather than blocking.
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
     * Registers the {@code sb/*} verbs on the command inbox through the scope-aware registration
     * form ({@link CommandInbox#registerScoped}): the inbox subscribes both D‑U28 command scopes and
     * hands each handler the delivery topic's {@code {instance}} token ({@code null} for a
     * component-scoped delivery), which the {@link CommandRouter} treats as authoritative over the
     * {@code body.instance} selector (SOUTHBOUND.md §2.2). Idempotent per verb — the inbox rejects a
     * duplicate registration. No-op when the inbox is absent (a mock/test bring-up with no resolved
     * identity), so the adapter still comes up.
     */
    public void registerVerbs() {
        if (commands == null) {
            LOGGER.warn("No command inbox available - the sb/* command surface is disabled");
            return;
        }
        commands.registerScoped("sb/status", (req, inst) -> router.status(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/browse", (req, inst) -> router.browse(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/read", (req, inst) -> router.read(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/write", (req, inst) -> router.write(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/signals", (req, inst) -> router.signals(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/rescan", (req, inst) -> router.rescan(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/pause", (req, inst) -> router.pause(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("sb/resume", (req, inst) -> router.resume(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("reconnect", (req, inst) -> router.reconnect(CommandCodec.bodyOf(req), inst));
        commands.registerScoped("repoll", (req, inst) -> router.repoll(CommandCodec.bodyOf(req), inst));
        for (JsonObject panel : Panels.all()) {
            commands.registerPanel(panel);
        }
        LOGGER.info("Registered southbound command verbs: {}", commands.verbs());
    }
}
