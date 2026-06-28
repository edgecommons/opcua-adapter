"""Confirm the OPC UA data plane flows under a UserName identity against KEPServerEX.

The configured 'testuser' is permitted only the standard ns=0 Server diagnostics, so this watches
Server.ServerStatus.CurrentTime (ns=0, ticks each second) and asserts SouthboundTagUpdate messages
arrive with GOOD quality. (Subscribing to the ns=2 'Kepware Server' tags as this user requires a
User Manager permission grant in KEP.)
"""
import json
import sys
import time

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
updates = []


def on_connect(client, userdata, flags, reason_code, properties=None):
    client.subscribe("southbound/#")


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode())
    except Exception:
        return
    if payload.get("header", {}).get("name") == "SouthboundTagUpdate":
        updates.append((msg.topic, payload))


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="kep-user-validator")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    print("[U] waiting up to 30s for SouthboundTagUpdate under testuser...", flush=True)
    deadline = time.time() + 30
    while time.time() < deadline and len(updates) < 3:
        time.sleep(0.5)
    time.sleep(1)

    tag_ids = {p["body"]["tag"]["address"].get("nodeId") for _, p in updates}
    uris = {p["body"]["tag"]["address"].get("namespaceUri") for _, p in updates}
    quals = {s.get("quality") for _, p in updates for s in p["body"]["samples"]}
    print(f"[U] {len(updates)} updates, tags={sorted(str(t) for t in tag_ids)}, uris={uris}, qualities={quals}", flush=True)

    c.loop_stop()
    c.disconnect()

    ok = len(updates) > 0 and "GOOD" in quals
    print("\n===== KEP USERNAME DATA-PLANE =====", flush=True)
    print(f"  {'PASS' if ok else 'FAIL'}  data flows under UserName identity", flush=True)
    print(f"===== {'PASS' if ok else 'FAIL'} =====", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
