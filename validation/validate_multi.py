"""Validate one adapter bridging TWO OPC UA servers at once (config-kep-multi.json):
  instance 'sim1' -> asyncua sim (opc.tcp://localhost:4840, ns urn:edgecommons:sim)
  instance 'kep1' -> KEPServerEX (opc.tcp://192.168.1.180:49320, ns "Kepware Server")

Checks both stream concurrently with the correct per-instance identity (device.instance / endpoint /
namespaceUri), and that on-demand reads on each instance's topic route to that server only.

Prereqs: sim running (opcua_sim_server.py), KEP reachable + kep_setup.py run, adapter on
config-kep-multi.json, EMQX on localhost:1883.
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
SIM_NS = "urn:edgecommons:sim"
KEP_NS = "Kepware Server"

msgs = []
checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def on_connect(c, u, f, rc, p=None):
    c.subscribe("southbound/#")


def on_message(c, u, msg):
    try:
        msgs.append((msg.topic, json.loads(msg.payload.decode())))
    except Exception:
        pass


def updates():
    return [p for _, p in msgs if p.get("header", {}).get("name") == "SouthboundSignalUpdate"]


def by_instance(inst):
    return [p for p in updates() if p.get("body", {}).get("device", {}).get("instance") == inst]


def request(c, topic, body, timeout=5):
    cid = str(uuid.uuid4())
    reply = f"southbound/reply/multi/{cid}"
    h = {"name": "ReadSignals", "version": "1.0", "timestamp": datetime.now(timezone.utc).isoformat(),
         "uuid": str(uuid.uuid4()), "correlation_id": cid, "reply_to": reply}
    c.publish(topic, json.dumps({"header": h, "tags": {}, "body": body}))
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, p in list(msgs):
            if t == reply and p.get("header", {}).get("correlation_id") == cid:
                return p
        time.sleep(0.1)
    return None


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="multi")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    print("[*] waiting up to 40s for BOTH instances to stream...", flush=True)
    deadline = time.time() + 40
    while time.time() < deadline:
        if by_instance("sim1") and by_instance("kep1"):
            break
        time.sleep(0.5)
    time.sleep(2)

    sim = by_instance("sim1")
    kep = by_instance("kep1")
    check("sim1 streaming", len(sim) > 0, f"{len(sim)} updates")
    check("kep1 streaming", len(kep) > 0, f"{len(kep)} updates")

    def field(ps, path):
        out = set()
        for p in ps:
            d = p.get("body", {})
            for k in path:
                d = d.get(k, {}) if isinstance(d, dict) else {}
            if isinstance(d, str):
                out.add(d)
        return out

    sim_eps = field(sim, ["device", "endpoint"])
    kep_eps = field(kep, ["device", "endpoint"])
    sim_uris = {p["body"]["signal"]["address"].get("namespaceUri") for p in sim}
    kep_uris = {p["body"]["signal"]["address"].get("namespaceUri") for p in kep}
    sim_signals = {p["body"]["signal"]["address"].get("nodeId") for p in sim}
    kep_signals = {p["body"]["signal"]["address"].get("nodeId") for p in kep}

    check("sim1 identity", any("localhost:4840" in e for e in sim_eps) and sim_uris == {SIM_NS},
          f"eps={sim_eps} uris={sim_uris}")
    check("kep1 identity", any("192.168.1.180" in e for e in kep_eps) and kep_uris == {KEP_NS},
          f"eps={kep_eps} uris={kep_uris}")
    check("distinct endpoints", sim_eps and kep_eps and sim_eps.isdisjoint(kep_eps), f"{sim_eps} vs {kep_eps}")
    check("sim1 signals are Sine*", any((t or "").startswith("Sine") for t in sim_signals), f"{sorted(t for t in sim_signals if t)}")
    check("kep1 signals are Live*", any("Live" in (t or "") for t in kep_signals), f"{sorted(t for t in kep_signals if t)}")

    # derive component name from a sim1 topic: southbound/<site>/<comp>/sim1/<signal>
    comp = None
    for t, p in msgs:
        parts = t.split("/")
        if len(parts) >= 5 and parts[3] == "sim1":
            comp = parts[2]
            break
    if comp:
        # on-demand read on EACH instance's own topic resolves only that server's signal
        rs = request(c, f"southbound/{comp}/sim1/read", {"signals": [{"namespaceUri": SIM_NS, "signalId": "Counter"}]})
        sids = {e["signal"]["address"].get("nodeId"): e.get("quality") for e in (rs.get("body", {}).get("reads", []) if rs else [])}
        check("sim1 read routes to sim", sids.get("Counter") == "GOOD", f"{sids}")

        rk = request(c, f"southbound/{comp}/kep1/read", {"signals": [{"namespaceUri": KEP_NS, "signalId": "EdgeCommonsTest.Device1.Int32"}]})
        kids = {e["signal"]["address"].get("nodeId"): e.get("quality") for e in (rk.get("body", {}).get("reads", []) if rk else [])}
        check("kep1 read routes to kep", kids.get("EdgeCommonsTest.Device1.Int32") == "GOOD", f"{kids}")
    else:
        check("component derivable", False, "no sim1 topic seen")

    c.loop_stop()
    c.disconnect()
    print("\n================ MULTI-SERVER RESULTS ================", flush=True)
    npass = nfail = 0
    for name, ok, detail in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {name:28} {detail}", flush=True)
        npass += ok
        nfail += not ok
    print(f"\n========== {npass}/{npass + nfail} PASS ({'ALL PASS' if nfail == 0 else str(nfail) + ' FAIL'}) ==========", flush=True)
    sys.exit(0 if nfail == 0 else 1)


if __name__ == "__main__":
    main()
