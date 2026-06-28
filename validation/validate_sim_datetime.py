"""Validate DateTime read + write round-trip through the adapter against the asyncua sim.

KEPServerEX's Simulator cannot host a writable Date tag, so the DateTime *write* path is verified
here against the sim's writable DateTimeRW node (ns=urn:ggcommons:sim). Run:

    python validation/opcua_sim_server.py &
    java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
         validation/messaging-local.json -c FILE validation/config.json -t sim-thing &
    python validation/validate_sim_datetime.py
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
NS = "urn:ggcommons:sim"
TAG = "DateTimeRW"
NEW = "2030-06-15T08:09:10+00:00"

msgs = []


def on_connect(c, u, f, rc, p=None):
    c.subscribe("southbound/#")


def on_message(c, u, msg):
    try:
        msgs.append((msg.topic, json.loads(msg.payload.decode())))
    except Exception:
        pass


def updates():
    return [(t, p) for t, p in msgs if p.get("header", {}).get("name") == "SouthboundTagUpdate"]


def request(c, topic, name, body, timeout=5):
    cid = str(uuid.uuid4())
    reply = f"southbound/reply/dt/{cid}"
    h = {"name": name, "version": "1.0", "timestamp": datetime.now(timezone.utc).isoformat(),
         "uuid": str(uuid.uuid4()), "correlation_id": cid, "reply_to": reply}
    c.publish(topic, json.dumps({"header": h, "tags": {}, "body": body}))
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, p in list(msgs):
            if t == reply and p.get("header", {}).get("correlation_id") == cid:
                return p
        time.sleep(0.1)
    return None


def read_dt(c, read_topic):
    rp = request(c, read_topic, "ReadTags", {"tags": [{"namespaceUri": NS, "tagId": TAG}]})
    for e in (rp.get("body", {}).get("reads", []) if rp else []):
        if e.get("tag", {}).get("address", {}).get("nodeId") == TAG:
            return e.get("value"), e.get("quality")
    return None, None


def iso(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")) if isinstance(s, str) else None


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="sim-dt")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    print("[*] waiting up to 30s for adapter readiness...", flush=True)
    deadline = time.time() + 30
    while time.time() < deadline and not updates():
        time.sleep(0.5)
    if not updates():
        print("FAIL: no updates; is the adapter running on config.json against the sim?", flush=True)
        sys.exit(1)
    parts = updates()[0][0].split("/")
    comp, inst = parts[2], parts[3]
    read_topic = f"southbound/{comp}/{inst}/read"
    write_topic = f"southbound/{comp}/{inst}/write"

    results = {}
    before, q = read_dt(c, read_topic)
    results["read_iso"] = isinstance(before, str) and "DateTime{" not in before and iso(before) is not None
    print(f"[1] DateTimeRW before = {before!r} ({q})", flush=True)

    c.publish(write_topic, json.dumps({"header": {"name": "WriteTags", "correlation_id": str(uuid.uuid4())},
                                       "tags": {}, "body": {"writes": [{"namespaceUri": NS, "tagId": TAG, "value": NEW}]}}))
    time.sleep(1.5)
    after, q2 = read_dt(c, read_topic)
    print(f"[2] wrote {NEW!r} -> read {after!r} ({q2})", flush=True)
    results["write_roundtrip"] = iso(after) is not None and iso(after) == iso(NEW)

    c.loop_stop()
    c.disconnect()
    print("\n===== SIM DATETIME =====", flush=True)
    ok = True
    for k, v in results.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}", flush=True)
        ok = ok and v
    print(f"===== {'ALL PASS' if ok else 'FAIL'} =====", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
