"""Write -> read-back a single KEP tag through the adapter, to validate the write path.

Reads the tag's current value (to learn its type), writes a new value of the same type, then reads it
back and asserts it changed. Tag + namespace are set below.
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
REPLY = "southbound/reply/kepwrite"
NS = "Kepware Server"
TAG = "Channel1.Device1.Tag2"

updates = []
reads = []


def on_connect(c, u, f, rc, p=None):
    c.subscribe("southbound/#")


def on_message(c, u, msg):
    try:
        pl = json.loads(msg.payload.decode())
    except Exception:
        return
    n = pl.get("header", {}).get("name")
    if n == "SouthboundTagUpdate":
        updates.append((msg.topic, pl))
    elif n == "SouthboundReadResult":
        reads.append(pl)


def env(name, body, reply_to=None):
    h = {"name": name, "version": "1.0", "timestamp": datetime.now(timezone.utc).isoformat(),
         "uuid": str(uuid.uuid4()), "correlation_id": str(uuid.uuid4())}
    if reply_to:
        h["reply_to"] = reply_to
    return json.dumps({"header": h, "tags": {}, "body": body})


def read_tag(c, read_topic):
    reads.clear()
    c.publish(read_topic, env("ReadTags", {"tags": [{"namespaceUri": NS, "tagId": TAG}]}, REPLY))
    time.sleep(2.5)
    for r in reads:
        for e in r.get("body", {}).get("reads", []):
            if e.get("tag", {}).get("address", {}).get("nodeId") == TAG:
                return e
    return None


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="kep-write-validator")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    print("[*] waiting for a SouthboundTagUpdate to derive topics...", flush=True)
    deadline = time.time() + 30
    while time.time() < deadline and not updates:
        time.sleep(0.5)
    if not updates:
        print("FAIL: no updates; adapter not publishing", flush=True)
        sys.exit(1)
    t = updates[0][0].split("/")
    comp, inst = t[2], t[3]
    read_topic = f"southbound/{comp}/{inst}/read"
    write_topic = f"southbound/{comp}/{inst}/write"
    print(f"[*] read={read_topic} write={write_topic}", flush=True)

    before = read_tag(c, read_topic)
    if not before:
        print(f"FAIL: {TAG} not found/readable (does it exist? read permission?)", flush=True)
        c.loop_stop()
        sys.exit(1)
    cur = before.get("value")
    print(f"[1] {TAG} before = {cur!r} ({before.get('quality')})", flush=True)

    if isinstance(cur, bool):
        newv = not cur
    elif isinstance(cur, (int, float)):
        newv = (cur or 0) + 1
    else:
        newv = "ggcommons-" + datetime.now(timezone.utc).strftime("%H%M%S")
    print(f"[2] writing {TAG} = {newv!r}", flush=True)
    c.publish(write_topic, env("WriteTags", {"writes": [{"namespaceUri": NS, "tagId": TAG, "value": newv}]}))
    time.sleep(2)

    after = read_tag(c, read_topic)
    av = after.get("value") if after else None
    aq = after.get("quality") if after else None
    print(f"[3] {TAG} after  = {av!r} ({aq})", flush=True)

    if isinstance(newv, bool):
        ok = bool(av) == newv
    elif isinstance(newv, (int, float)):
        ok = av is not None and abs(float(av) - float(newv)) < 1e-6
    else:
        ok = av == newv

    c.loop_stop()
    c.disconnect()
    print("\n===== KEP WRITE =====", flush=True)
    print(f"  {'PASS' if ok else 'FAIL'}  write -> read-back {TAG}", flush=True)
    print(f"===== {'PASS' if ok else 'FAIL'} =====", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
