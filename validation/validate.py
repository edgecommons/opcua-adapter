"""Smoke-validate the OPC UA adapter end-to-end over the local EMQX broker.

Phase A: observe SouthboundSignalUpdate messages (browse+subscribe+publish+Tier-1 envelope).
Phase B: on-demand batch read (request/reply) of Counter + Setpoint.
Phase C: batch write Setpoint=42.5, then read it back to confirm the write landed.
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
REPLY_TOPIC = "southbound/reply/smoke"

updates = []   # SouthboundSignalUpdate
reads = []     # SouthboundReadResult


def on_connect(client, userdata, flags, reason_code, properties=None):
    client.subscribe("southbound/#")


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode())
    except Exception:
        return
    name = payload.get("header", {}).get("name")
    if name == "SouthboundSignalUpdate":
        updates.append((msg.topic, payload))
    elif name == "SouthboundReadResult":
        reads.append(payload)


def envelope(name, body, reply_to=None):
    cid = str(uuid.uuid4())
    header = {
        "name": name, "version": "1.0",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "uuid": str(uuid.uuid4()), "correlation_id": cid,
    }
    if reply_to:
        header["reply_to"] = reply_to
    return cid, json.dumps({"header": header, "tags": {}, "body": body})


def setpoint_from_reads():
    for r in reads:
        for entry in r.get("body", {}).get("reads", []):
            addr = entry.get("signal", {}).get("address", {})
            if addr.get("nodeId") == "Setpoint":
                return entry.get("value"), entry.get("quality")
    return None, None


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="smoke-validator")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    results = {}

    # ---- Phase A: wait for adapter readiness, then observe ---------------------------------
    print("[A] waiting up to 40s for first SouthboundSignalUpdate...", flush=True)
    deadline = time.time() + 40
    while time.time() < deadline and len(updates) < 6:
        time.sleep(0.5)
    time.sleep(2)  # settle, collect a few more
    signal_names = {p["body"]["signal"]["address"].get("nodeId") for _, p in updates}
    qualities = {s.get("quality") for _, p in updates for s in p["body"]["samples"]}
    results["A_updates_received"] = len(updates) > 0
    results["A_has_sine_signals"] = any(n and n.startswith("Sine") for n in signal_names)
    results["A_quality_good"] = "GOOD" in qualities
    print(f"[A] {len(updates)} updates, signals={sorted(t for t in signal_names if t)}, qualities={qualities}", flush=True)

    if not updates:
        print("[A] no updates -- cannot derive topics; aborting B/C", flush=True)
        summarize(results)
        return

    # derive resolved component/instance from an observed topic: southbound/<site>/<comp>/<inst>/<signal>
    sample_topic = updates[0][0].split("/")
    comp, inst = sample_topic[2], sample_topic[3]
    write_topic = f"southbound/{comp}/{inst}/write"
    read_topic = f"southbound/{comp}/{inst}/read"
    print(f"[*] resolved write={write_topic} read={read_topic}", flush=True)

    # ---- Phase B: on-demand batch read ----------------------------------------------------
    print("[B] on-demand read of Counter + Setpoint...", flush=True)
    reads.clear()
    _, req = envelope("ReadSignals",
                      {"signals": [{"ns": 2, "signalId": "Counter"}, {"ns": 2, "signalId": "Setpoint"}]},
                      reply_to=REPLY_TOPIC)
    c.publish(read_topic, req)
    time.sleep(2)
    read_signals = {e["signal"]["address"].get("nodeId") for r in reads for e in r.get("body", {}).get("reads", [])}
    results["B_read_reply_received"] = len(reads) > 0
    results["B_read_has_counter_setpoint"] = {"Counter", "Setpoint"}.issubset(read_signals)
    print(f"[B] reply(s)={len(reads)}, signals={sorted(t for t in read_signals if t)}", flush=True)

    # ---- Phase C: batch write Setpoint then read back -------------------------------------
    print("[C] batch write Setpoint=42.5, then read back...", flush=True)
    _, w = envelope("WriteSignals", {"writes": [{"ns": 2, "signalId": "Setpoint", "value": 42.5}]})
    c.publish(write_topic, w)
    time.sleep(1.5)
    reads.clear()
    _, req2 = envelope("ReadSignals", {"signals": [{"ns": 2, "signalId": "Setpoint"}]}, reply_to=REPLY_TOPIC)
    c.publish(read_topic, req2)
    time.sleep(2)
    val, qual = setpoint_from_reads()
    results["C_write_then_read_matches"] = (val is not None and abs(float(val) - 42.5) < 1e-6)
    print(f"[C] Setpoint after write = {val} (quality={qual})", flush=True)

    c.loop_stop()
    c.disconnect()
    summarize(results)


def summarize(results):
    print("\n===== SMOKE RESULTS =====", flush=True)
    ok = True
    for k, v in results.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}", flush=True)
        ok = ok and v
    print(f"===== {'ALL PASS' if ok else 'FAILURES PRESENT'} =====", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
