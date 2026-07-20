package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pure, protocol-free dispatcher behind the component-level {@code sb/*} command surface: it owns
 * instance routing (D‑EIP‑13 / D‑U28), the standardized error-code family, the lifecycle-verb reply
 * shapes, the {@code repoll}-while-paused refusal, and the per-verb command-metric recording — all
 * written against the {@link DeviceSession} seam so it is unit-tested with a fake session, no live OPC UA
 * server required.
 *
 * <p>{@link CommandRegistry} wires these methods onto the library command inbox; this class never
 * touches the inbox, Milo, or the messaging layer.
 *
 * <h2>Standardized error codes (SOUTHBOUND.md §2.2)</h2>
 * <ul>
 *   <li>{@link #ERR_NO_SUCH_INSTANCE} — the request named an instance that is not configured/connected.</li>
 *   <li>{@link #ERR_BAD_ARGS} — a missing instance selector when several are configured, {@code repoll}
 *       while paused, or a malformed argument.</li>
 * </ul>
 * (Verb-specific codes such as {@code DEVICE_UNAVAILABLE}, {@code RECONNECT_FAILED}, and the write/read
 * codes are raised by the {@link DeviceSession} implementation and propagate through unchanged.)
 */
public class CommandRouter {

    /** Error code: the request named (or defaulted to) an instance that is not configured/connected. */
    public static final String ERR_NO_SUCH_INSTANCE = "NO_SUCH_INSTANCE";

    /** Error code: a malformed argument, a missing instance selector, or a nonsensical request. */
    public static final String ERR_BAD_ARGS = "BAD_ARGS";

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
     * Route to the addressed device: {@code body.instance} is optional iff exactly one device is
     * connected; with two or more a missing selector is {@link #ERR_BAD_ARGS} and an unknown id is
     * {@link #ERR_NO_SUCH_INSTANCE}. When none are connected yet, {@link #ERR_NO_SUCH_INSTANCE}.
     */
    public synchronized DeviceSession resolve(JsonObject body) throws CommandException {
        String instance = instanceOf(body);
        if (instance == null) {
            if (devices.size() == 1) {
                return devices.values().iterator().next();
            }
            if (devices.isEmpty()) {
                throw new CommandException(ERR_NO_SUCH_INSTANCE, "no device instance is connected yet");
            }
            throw new CommandException(ERR_BAD_ARGS, "field `instance` is required when multiple instances"
                    + " are connected (" + devices.keySet() + ")");
        }
        DeviceSession device = devices.get(instance);
        if (device == null) {
            throw new CommandException(ERR_NO_SUCH_INSTANCE, "instance '" + instance
                    + "' is not connected (connected: " + devices.keySet() + ")");
        }
        return device;
    }

    private static String instanceOf(JsonObject body) {
        if (body != null && body.has("instance") && !body.get("instance").isJsonNull()) {
            return body.get("instance").getAsString();
        }
        return null;
    }

    // ---- verb dispatch (route → call → record → reply) -------------------------------------------

    public JsonObject status(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        return record(d, d.status());
    }

    public JsonObject signals(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        return record(d, d.signals());
    }

    public JsonObject browse(JsonObject body) throws Exception {
        DeviceSession d = resolve(body);
        try {
            JsonObject out = d.browse(body);
            d.recordCommand(true);
            return out;
        } catch (Exception e) {
            d.recordCommand(false);
            throw e;
        }
    }

    public JsonObject read(JsonObject body) throws Exception {
        DeviceSession d = resolve(body);
        try {
            JsonObject out = d.read(body);
            d.recordCommand(true);
            return out;
        } catch (Exception e) {
            d.recordCommand(false);
            throw e;
        }
    }

    public JsonObject write(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        return record(d, d.write(body));
    }

    public JsonObject rescan(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        return record(d, d.rescan());
    }

    public JsonObject pause(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        boolean changed = d.pause();
        d.recordCommand(true);
        return lifecycle(d.id(), "paused", true, changed);
    }

    public JsonObject resume(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        boolean changed = d.resume();
        d.recordCommand(true);
        return lifecycle(d.id(), "paused", false, changed);
    }

    public JsonObject reconnect(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
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

    public JsonObject repoll(JsonObject body) throws CommandException {
        DeviceSession d = resolve(body);
        if (d.isPaused()) {
            d.recordCommand(false);
            throw new CommandException(ERR_BAD_ARGS, "instance is paused - resume before repolling");
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
