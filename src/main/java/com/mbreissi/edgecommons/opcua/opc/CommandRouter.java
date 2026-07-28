package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pure, protocol-free dispatcher behind the component-level {@code sb/*} command surface: it owns
 * the component-side half of instance resolution (the configured default and the existence check —
 * SOUTHBOUND.md §2.2 / D‑SC‑4), the standardized error-code family, the lifecycle-verb reply shapes,
 * the {@code repoll}-while-paused refusal, and the per-verb command-metric recording — all written
 * against the {@link DeviceSession} seam so it is unit-tested with a fake session, no live OPC UA
 * server required.
 *
 * <p><b>Addressing is the library's.</b> Every {@code sb/*} verb declares
 * {@link com.mbreissi.edgecommons.commands.CommandScope#INSTANCE}, so the command inbox extracts the
 * delivery topic's {@code {instance}} token and the body's {@code instance} field, refuses a conflict
 * between the two with {@code BAD_ARGS} <i>before</i> dispatch, and hands each handler the resolved
 * {@code addressedInstance} (topic token, else body field, else {@code null}). This class therefore
 * never parses a topic or a body selector — it starts from that token.
 *
 * <p>{@link CommandRegistry} wires these methods onto the library command inbox; this class never
 * touches the inbox, Milo, or the messaging layer.
 *
 * <h2>Standardized error codes (SOUTHBOUND.md §2.2)</h2>
 * <ul>
 *   <li>{@link #ERR_NO_SUCH_INSTANCE} — the request addressed an instance that is not configured/connected.</li>
 *   <li>{@link #ERR_BAD_ARGS} — no addressed instance when several are connected, or a malformed
 *       argument.</li>
 *   <li>{@link #ERR_PAUSED} — a whole-operation the paused state prohibits ({@code repoll} while
 *       paused).</li>
 * </ul>
 * (Verb-specific codes such as {@code DEVICE_UNAVAILABLE}, {@code RECONNECT_FAILED}, and the write/read
 * codes are raised by the {@link DeviceSession} implementation and propagate through unchanged.)
 */
public class CommandRouter {

    /** Error code: the request named (or defaulted to) an instance that is not configured/connected. */
    public static final String ERR_NO_SUCH_INSTANCE = "NO_SUCH_INSTANCE";

    /** Error code: a malformed argument, an unaddressed multi-instance request, or a nonsensical request. */
    public static final String ERR_BAD_ARGS = "BAD_ARGS";

    /** Error code: a whole-operation the paused state prohibits ({@code repoll} while paused). */
    public static final String ERR_PAUSED = "PAUSED";

    /** Error code: the device session is down (raised by the {@link DeviceSession} implementation). */
    public static final String ERR_DEVICE_UNAVAILABLE = "DEVICE_UNAVAILABLE";

    /** Error code: an immediate {@code reconnect} attempt failed. */
    public static final String ERR_RECONNECT_FAILED = "RECONNECT_FAILED";

    private final Map<String, DeviceSession> devices = new LinkedHashMap<>();

    /** Registers a connected device instance so its verbs become routable. */
    public synchronized void addDevice(DeviceSession device) {
        devices.put(device.id(), device);
    }

    synchronized int deviceCount() {
        return devices.size();
    }

    /**
     * Resolve the addressed device — the component-side half of the addressing contract
     * (SOUTHBOUND.md §2.2 / D‑SC‑4). The library has already resolved {@code addressedInstance} from
     * the delivery topic or the request body and rejected any conflict between the two; what is left
     * needs <i>this adapter's configuration</i>, which the library does not have:
     *
     * <ul>
     *   <li>an addressed instance that names no connected device is {@link #ERR_NO_SUCH_INSTANCE};</li>
     *   <li>no addressed instance at all resolves to the sole connected device when exactly one is
     *       connected (the optional-iff-one default), is {@link #ERR_BAD_ARGS} when several are, and
     *       is {@link #ERR_NO_SUCH_INSTANCE} when none has connected yet.</li>
     * </ul>
     *
     * @param addressedInstance the library-resolved addressed instance, or {@code null} when the
     *                          command named no instance on the topic or in the body
     */
    public synchronized DeviceSession resolve(String addressedInstance) throws CommandException {
        if (addressedInstance == null) {
            if (devices.size() == 1) {
                return devices.values().iterator().next();
            }
            if (devices.isEmpty()) {
                throw new CommandException(ERR_NO_SUCH_INSTANCE, "no device instance is connected yet");
            }
            throw new CommandException(ERR_BAD_ARGS, "field `instance` is required when multiple instances"
                    + " are connected (" + devices.keySet() + ")");
        }
        DeviceSession device = devices.get(addressedInstance);
        if (device == null) {
            throw new CommandException(ERR_NO_SUCH_INSTANCE, "instance '" + addressedInstance
                    + "' is not connected (connected: " + devices.keySet() + ")");
        }
        return device;
    }

    // ---- verb dispatch (resolve → call → record → reply) -----------------------------------------
    // Every verb takes the library-resolved addressed instance (null when the command named none),
    // plus the request body where the verb itself has arguments.

    public JsonObject status(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        return record(d, d.status());
    }

    public JsonObject signals(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        return record(d, d.signals());
    }

    public JsonObject browse(JsonObject body, String addressedInstance) throws Exception {
        DeviceSession d = resolve(addressedInstance);
        try {
            JsonObject out = d.browse(body);
            d.recordCommand(true);
            return out;
        } catch (Exception e) {
            d.recordCommand(false);
            throw e;
        }
    }

    public JsonObject read(JsonObject body, String addressedInstance) throws Exception {
        DeviceSession d = resolve(addressedInstance);
        try {
            JsonObject out = d.read(body);
            d.recordCommand(true);
            return out;
        } catch (Exception e) {
            d.recordCommand(false);
            throw e;
        }
    }

    public JsonObject write(JsonObject body, String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        return record(d, d.write(body));
    }

    public JsonObject rescan(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        return record(d, d.rescan());
    }

    public JsonObject pause(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        boolean changed = d.pause();
        d.recordCommand(true);
        return lifecycle(d.id(), "paused", true, changed);
    }

    public JsonObject resume(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        boolean changed = d.resume();
        d.recordCommand(true);
        return lifecycle(d.id(), "paused", false, changed);
    }

    public JsonObject reconnect(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        try {
            boolean connected = d.reconnect();
            d.recordCommand(true);
            JsonObject out = new JsonObject();
            out.addProperty("id", d.id());
            out.addProperty("connected", connected);
            return out;
        } catch (CommandException e) {
            d.recordCommand(false);
            throw e;
        }
    }

    public JsonObject repoll(String addressedInstance) throws CommandException {
        DeviceSession d = resolve(addressedInstance);
        if (d.isPaused()) {
            d.recordCommand(false);
            throw new CommandException(ERR_PAUSED, "instance is paused - resume before repolling");
        }
        try {
            long polled = d.repoll();
            d.recordCommand(true);
            JsonObject out = new JsonObject();
            out.addProperty("id", d.id());
            out.addProperty("polled", polled);
            return out;
        } catch (CommandException e) {
            d.recordCommand(false);
            throw e;
        }
    }

    private static JsonObject record(DeviceSession d, JsonObject result) {
        d.recordCommand(true);
        return result;
    }

    private static JsonObject lifecycle(String id, String key, boolean value, boolean changed) {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty(key, value);
        out.addProperty("changed", changed);
        return out;
    }
}
