package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonObject;

/**
 * Emits an operator-facing UNS <b>{@code evt}</b> (event/alarm) for one device instance, addressed to
 * {@code ecv1/{device}/{component}/{instance}/evt/{channel}} (docs/SOUTHBOUND.md §4). The
 * implementation ({@link OpcUaDevice}) mints the topic through the instance-scoped {@code uns()}
 * builder and publishes best-effort — a failed event never disturbs the data plane or a command reply.
 *
 * <p>Channels follow a {@code {severity}/{name}} convention, e.g. {@code "critical/connection-lost"},
 * {@code "connection-restored"}, {@code "warning/write-rejected"}, {@code "warning/signal-stale"}.
 */
@FunctionalInterface
public interface EventEmitter {

    /**
     * Publishes an event on the given {@code evt} channel.
     *
     * @param channel the evt channel (one or more {@code /}-separated tokens after {@code evt/})
     * @param body    the event body
     */
    void emit(String channel, JsonObject body);
}
