"""Smoke-validate the OPC UA adapter end-to-end against a live KEPServerEX over EMQX.

Targets KEP's built-in system tags (no device channels required):
  Phase A: subscribe to _System._Time* (ticks every second) -> SouthboundTagUpdate, GOOD quality,
           and the stable namespaceUri ("Kepware Server") carried in tag.address.
  Phase B: on-demand read of _System._ProductName + _System._ActiveTagCount, addressed by
           namespaceUri (not a literal index) -> SouthboundReadResult.

Write (Phase C) is intentionally omitted: _System.* tags are read-only. Add a writable tag on a
Channel/Device in KEP to exercise the write path.
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
REPLY_TOPIC = "southbound/reply/kep"
NS_URI = "Kepware Server"

updates = []   # SouthboundTagUpdate
reads = []     # SouthboundReadResult


def on_connect(client, userdata, flags, reason_code, properties=None):
    client.subscribe("southbound/#")


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode())
    except Exception:
        return
    name = payload.get("header", {}).get("name")
    if name == "SouthboundTagUpdate":
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


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="kep-validator")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    results = {}

    # ---- Phase A: observe ticking system-clock tags ---------------------------------------
    print("[A] waiting up to 45s for first SouthboundTagUpdate from KEP...", flush=True)
    deadline = time.time() + 45
    while time.time() < deadline and len(updates) < 4:
        time.sleep(0.5)
    time.sleep(2)
    tag_ids = {p["body"]["tag"]["address"].get("nodeId") for _, p in updates}
    uris = {p["body"]["tag"]["address"].get("namespaceUri") for _, p in updates}
    qualities = {s.get("quality") for _, p in updates for s in p["body"]["samples"]}
    results["A_updates_received"] = len(updates) > 0
    results["A_has_time_tags"] = any(t and t.startswith("_System._Time") for t in tag_ids)
    results["A_namespaceUri_in_address"] = NS_URI in uris
    results["A_quality_good"] = "GOOD" in qualities
    print(f"[A] {len(updates)} updates, tags={sorted(t for t in tag_ids if t)}", flush=True)
    print(f"[A] namespaceUris={uris}, qualities={qualities}", flush=True)

    if not updates:
        print("[A] no updates -- cannot derive topics; aborting B", flush=True)
        summarize(results)
        return

    sample_topic = updates[0][0].split("/")
    comp, inst = sample_topic[2], sample_topic[3]
    read_topic = f"southbound/{comp}/{inst}/read"
    print(f"[*] resolved read={read_topic}", flush=True)

    # ---- Phase B: on-demand read addressed by namespaceUri --------------------------------
    print("[B] read _System._ProductName + _System._ActiveTagCount by namespaceUri...", flush=True)
    reads.clear()
    _, req = envelope("ReadTags",
                      {"tags": [{"namespaceUri": NS_URI, "tagId": "_System._ProductName"},
                                {"namespaceUri": NS_URI, "tagId": "_System._ActiveTagCount"}]},
                      reply_to=REPLY_TOPIC)
    c.publish(read_topic, req)
    time.sleep(3)
    read_entries = {e["tag"]["address"].get("nodeId"): e for r in reads for e in r.get("body", {}).get("reads", [])}
    results["B_read_reply_received"] = len(reads) > 0
    results["B_read_has_both"] = {"_System._ProductName", "_System._ActiveTagCount"}.issubset(read_entries.keys())
    pn = read_entries.get("_System._ProductName", {})
    results["B_read_resolved_uri"] = pn.get("tag", {}).get("address", {}).get("namespaceUri") == NS_URI
    print(f"[B] reply(s)={len(reads)}, tags={sorted(read_entries.keys())}", flush=True)
    if pn:
        print(f"[B] _System._ProductName = {pn.get('value')!r} ({pn.get('quality')}), "
              f"address={pn.get('tag', {}).get('address')}", flush=True)

    c.loop_stop()
    c.disconnect()
    summarize(results)


def summarize(results):
    print("\n===== KEP SMOKE RESULTS =====", flush=True)
    ok = True
    for k, v in results.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}", flush=True)
        ok = ok and v
    print(f"===== {'ALL PASS' if ok else 'FAILURES PRESENT'} =====", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
