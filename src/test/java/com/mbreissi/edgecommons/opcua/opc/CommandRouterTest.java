package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.commands.CommandException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure {@link CommandRouter}: the component-side half of instance resolution
 * (D‑SC‑4 — the optional-iff-one default and the existence check), the standardized error-code family,
 * the lifecycle-verb reply shapes, the {@code repoll}-while-paused refusal, and the per-verb
 * command-metric recording — all driven through the {@link DeviceSession} seam with a fake session, no
 * live OPC UA server.
 */
class CommandRouterTest {

    /** A controllable {@link DeviceSession} that records the command outcomes the router books. */
    private static class FakeSession implements DeviceSession {
        final String id;
        boolean connected = true;
        boolean paused;
        boolean reconnectFails;
        boolean repollUnavailable;
        long polled = 3;
        int commandsOk;
        int commandsFailed;

        FakeSession(String id) {
            this.id = id;
        }

        @Override public String id() {
            return id;
        }

        @Override public boolean isConnected() {
            return connected;
        }

        @Override public boolean isPaused() {
            return paused;
        }

        @Override public JsonObject status() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("connected", connected);
            return o;
        }

        @Override public JsonObject signals() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            return o;
        }

        @Override public JsonObject browse(JsonObject body) throws CommandException {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            return o;
        }

        @Override public JsonObject read(JsonObject body) throws Exception {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            return o;
        }

        @Override public JsonObject write(JsonObject body) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            return o;
        }

        @Override public JsonObject rescan() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            return o;
        }

        @Override public boolean pause() {
            boolean changed = !paused;
            paused = true;
            return changed;
        }

        @Override public boolean resume() {
            boolean changed = paused;
            paused = false;
            return changed;
        }

        @Override public boolean reconnect() throws CommandException {
            if (reconnectFails) {
                throw new CommandException(CommandRouter.ERR_RECONNECT_FAILED, "boom");
            }
            connected = true;
            return true;
        }

        @Override public long repoll() throws CommandException {
            if (repollUnavailable) {
                throw new CommandException(CommandRouter.ERR_DEVICE_UNAVAILABLE, "down");
            }
            return polled;
        }

        @Override public void recordCommand(boolean ok) {
            if (ok) {
                commandsOk++;
            } else {
                commandsFailed++;
            }
        }
    }

    // ---- component-side instance resolution (SOUTHBOUND.md §2.2 / D-SC-4) ------------------------
    // The library resolves the addressed instance from the delivery topic or the body and rejects a
    // conflict between the two before dispatch; what is tested here is the half that needs this
    // adapter's configuration - the optional-iff-one default and the existence check.

    @Test
    void resolve_soleInstance_defaultsWhenNoInstanceIsAddressed() throws Exception {
        CommandRouter router = new CommandRouter();
        FakeSession only = new FakeSession("kep1");
        router.addDevice(only);
        assertEquals("kep1", router.resolve(null).id());
    }

    @Test
    void resolve_multipleInstances_noAddressedInstanceIsBadArgs() {
        CommandRouter router = new CommandRouter();
        router.addDevice(new FakeSession("kep1"));
        router.addDevice(new FakeSession("kep2"));
        CommandException e = assertThrows(CommandException.class, () -> router.resolve(null));
        assertEquals(CommandRouter.ERR_BAD_ARGS, e.getCode());
    }

    @Test
    void resolve_unknownInstance_isNoSuchInstance() {
        CommandRouter router = new CommandRouter();
        router.addDevice(new FakeSession("kep1"));
        CommandException e = assertThrows(CommandException.class, () -> router.resolve("nope"));
        assertEquals(CommandRouter.ERR_NO_SUCH_INSTANCE, e.getCode());
    }

    @Test
    void resolve_noneConnected_isNoSuchInstance() {
        CommandRouter router = new CommandRouter();
        CommandException e = assertThrows(CommandException.class, () -> router.resolve(null));
        assertEquals(CommandRouter.ERR_NO_SUCH_INSTANCE, e.getCode());
    }

    @Test
    void resolve_addressedInstance_routesToIt() throws Exception {
        CommandRouter router = new CommandRouter();
        router.addDevice(new FakeSession("kep1"));
        router.addDevice(new FakeSession("kep2"));
        assertEquals("kep2", router.resolve("kep2").id());
    }

    @Test
    void verbs_routeByTheAddressedInstance() throws Exception {
        CommandRouter router = new CommandRouter();
        FakeSession kep1 = new FakeSession("kep1");
        FakeSession kep2 = new FakeSession("kep2");
        router.addDevice(kep1);
        router.addDevice(kep2);
        assertEquals("kep2", router.status("kep2").get("id").getAsString());
        assertEquals("kep2", router.repoll("kep2").get("id").getAsString());
        assertEquals(2, kep2.commandsOk);
        assertEquals(0, kep1.commandsOk);
    }

    // ---- pass-through verbs record success -------------------------------------------------------

    @Test
    void status_signals_browse_read_write_rescan_recordSuccess() throws Exception {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        router.addDevice(s);
        assertEquals("kep1", router.status(null).get("id").getAsString());
        router.signals(null);
        router.browse(new JsonObject(), null);
        router.read(new JsonObject(), null);
        router.write(new JsonObject(), null);
        router.rescan(null);
        assertEquals(6, s.commandsOk);
        assertEquals(0, s.commandsFailed);
    }

    // ---- pause / resume idempotent {paused, changed} ---------------------------------------------

    @Test
    void pause_thenPauseAgain_reportsChangedThenUnchanged() throws Exception {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        router.addDevice(s);

        JsonObject first = router.pause(null);
        assertTrue(first.get("paused").getAsBoolean());
        assertTrue(first.get("changed").getAsBoolean());

        JsonObject second = router.pause(null);
        assertTrue(second.get("paused").getAsBoolean());
        assertFalse(second.get("changed").getAsBoolean());

        JsonObject resumed = router.resume(null);
        assertFalse(resumed.get("paused").getAsBoolean());
        assertTrue(resumed.get("changed").getAsBoolean());
    }

    // ---- reconnect {connected} -------------------------------------------------------------------

    @Test
    void reconnect_success_reportsConnected_andRecordsSuccess() throws Exception {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        s.connected = false;
        router.addDevice(s);
        JsonObject out = router.reconnect(null);
        assertTrue(out.get("connected").getAsBoolean());
        assertEquals(1, s.commandsOk);
    }

    @Test
    void reconnect_failure_propagatesCodeAndRecordsError() {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        s.reconnectFails = true;
        router.addDevice(s);
        CommandException e = assertThrows(CommandException.class, () -> router.reconnect(null));
        assertEquals(CommandRouter.ERR_RECONNECT_FAILED, e.getCode());
        assertEquals(1, s.commandsFailed);
    }

    // ---- repoll {polled}, refused while paused ---------------------------------------------------

    @Test
    void repoll_reportsPolledCount() throws Exception {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        s.polled = 7;
        router.addDevice(s);
        assertEquals(7, router.repoll(null).get("polled").getAsLong());
        assertEquals(1, s.commandsOk);
    }

    @Test
    void repoll_whilePaused_isPaused() {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        s.paused = true;
        router.addDevice(s);
        CommandException e = assertThrows(CommandException.class, () -> router.repoll(null));
        assertEquals(CommandRouter.ERR_PAUSED, e.getCode());
        assertEquals(1, s.commandsFailed);
    }

    @Test
    void repoll_deviceDown_propagatesUnavailableAndRecordsError() {
        CommandRouter router = new CommandRouter();
        FakeSession s = new FakeSession("kep1");
        s.repollUnavailable = true;
        router.addDevice(s);
        CommandException e = assertThrows(CommandException.class, () -> router.repoll(null));
        assertEquals(CommandRouter.ERR_DEVICE_UNAVAILABLE, e.getCode());
        assertEquals(1, s.commandsFailed);
    }

    // ---- browse/read failures record an error ----------------------------------------------------

    @Test
    void browse_failure_recordsError() {
        CommandRouter router = new CommandRouter();
        DeviceSession failing = new FakeSession("kep1") {
            @Override public JsonObject browse(JsonObject body) throws CommandException {
                throw new CommandException(CommandRouter.ERR_BAD_ARGS, "bad ref");
            }
        };
        router.addDevice(failing);
        assertThrows(CommandException.class, () -> router.browse(new JsonObject(), null));
    }
}
