package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;

/**
 * The device seam: what one instance's southbound command surface routes through. It sits in front of
 * the live OPC UA layer ({@link OpcUaConnection} + {@link SubscriptionManager} + {@link CommandService})
 * and exposes exactly the browse/read/write/subscribe operations the standardized {@code sb/*} verb
 * family needs — <b>without</b> the caller (the {@link CommandRouter}) touching Eclipse Milo.
 *
 * <p><b>Why the seam exists.</b> The live implementation ({@link OpcUaDevice}) can only be exercised
 * against a real server (validated by {@code validation/} against KEPServerEX), so it is excluded from
 * the in-process coverage gate. Everything above the seam — instance routing, the standardized
 * error-code family, the lifecycle-verb reply shapes, {@code repoll}-while-paused refusal, and the
 * command-metric recording — is written against this interface and unit-tested with a fake session, so
 * the {@code sb/*} contract is proven without a live OPC UA server.
 *
 * <p>Coded failures surface as {@link CommandException} with a standardized code
 * ({@code NO_SUCH_INSTANCE}, {@code BAD_ARGS}, {@code DEVICE_UNAVAILABLE}, {@code RECONNECT_FAILED}, …);
 * the router propagates the code to the reply envelope.
 */
public interface DeviceSession {

    /** The instance id — the {@code {instance}} token of this device's UNS topics. */
    String id();

    /** Whether the live OPC UA session is up. */
    boolean isConnected();

    /** Whether telemetry production is currently suspended by {@code sb/pause}. */
    boolean isPaused();

    /** {@code sb/status}: instance/connection status + counters. */
    JsonObject status();

    /** {@code sb/signals}: the configured signal inventory (writable flag per entry), no device round-trip. */
    JsonObject signals();

    /** {@code sb/browse}: paged, hierarchical address-space browse. */
    JsonObject browse(JsonObject body) throws CommandException;

    /** {@code sb/read}: on-demand, ref-accepting read with regex include/exclude. */
    JsonObject read(JsonObject body) throws Exception;

    /** {@code sb/write}: confirmed, allow-listed batch write. */
    JsonObject write(JsonObject body);

    /** {@code sb/rescan}: re-browse the address space and refresh the node cache. */
    JsonObject rescan();

    /** {@code sb/pause}: suspend publishing. Returns whether the state changed (idempotent). */
    boolean pause();

    /** {@code sb/resume}: resume a paused instance. Returns whether the state changed (idempotent). */
    boolean resume();

    /**
     * {@code reconnect}: drop and re-establish the device session, one immediate attempt. Returns whether
     * the session is connected afterward.
     *
     * @throws CommandException {@code RECONNECT_FAILED} (the attempt failed) or {@code DEVICE_UNAVAILABLE}
     */
    boolean reconnect() throws CommandException;

    /**
     * {@code repoll}: an immediate explicit read of every subscribed signal, republished onto the
     * {@code data} class — a "refresh now" for this subscribe-model adapter. The router refuses it while
     * paused ({@code BAD_ARGS}) before this is called.
     *
     * @return the number of signals read
     * @throws CommandException {@code DEVICE_UNAVAILABLE} when the session is down
     */
    long repoll() throws CommandException;

    /** Record one {@code sb/*} command outcome into the {@code OpcUaCommand} metric family. */
    void recordCommand(boolean ok);
}
