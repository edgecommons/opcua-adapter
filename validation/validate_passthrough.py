"""Empirically confirm the adapter's PASS-THROUGH type behavior against asyncua:
read renders the value as a JSON string; write is rejected (skipped), leaving the value unchanged.

Covers OPC UA types the contract does not model: ByteString, Guid, NodeId, LocalizedText,
QualifiedName, StatusCode, XmlElement (all writable in the sim, so a skipped write is observable).

    python validation/opcua_sim_server.py &
    java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
         validation/messaging-local.json -c FILE validation/config.json -t sim-thing &
    python validation/validate_passthrough.py
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
NS = "urn:ggcommons:sim"
NODES = ["ByteStringNode", "GuidNode", "NodeIdNode", "LocalizedTextNode",
         "QualifiedNameNode", "StatusCodeNode", "XmlElementNode"]

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
    return [(t, p) for t, p in msgs if p.get("header", {}).get("name") == "SouthboundTagUpdate"]


def request(c, topic, body, timeout=5):
    cid = str(uuid.uuid4())
    reply = f"southbound/reply/pt/{cid}"
    h = {"name": "ReadTags", "version": "1.0", "timestamp": datetime.now(timezone.utc).isoformat(),
         "uuid": str(uuid.uuid4()), "correlation_id": cid, "reply_to": reply}
    c.publish(topic, json.dumps({"header": h, "tags": {}, "body": body}))
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, p in list(msgs):
            if t == reply and p.get("header", {}).get("correlation_id") == cid:
                return p
        time.sleep(0.1)
    return None


def read_all(c, read_topic):
    rp = request(c, read_topic, {"tags": [{"namespaceUri": NS, "tagId": n} for n in NODES]})
    return {e["tag"]["address"].get("nodeId"): e for e in (rp.get("body", {}).get("reads", []) if rp else [])}


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="passthrough")
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

    before = read_all(c, read_topic)
    # read: each value is a (non-empty) JSON string, quality GOOD
    for n in NODES:
        e = before.get(n)
        v = e.get("value") if e else None
        check(f"read {n}", isinstance(v, str) and (e or {}).get("quality") == "GOOD", f"value={v!r}")

    # write each (a string); the adapter must reject (skip) unsupported types -> value unchanged
    writes = [{"namespaceUri": NS, "tagId": n, "value": "OVERWRITE"} for n in NODES]
    c.publish(write_topic, json.dumps({"header": {"name": "WriteTags", "correlation_id": str(uuid.uuid4())},
                                       "tags": {}, "body": {"writes": writes}}))
    time.sleep(2)
    after = read_all(c, read_topic)
    for n in NODES:
        b = (before.get(n) or {}).get("value")
        a = (after.get(n) or {}).get("value")
        check(f"write rejected {n}", a == b and a != "OVERWRITE", f"after={a!r}")

    c.loop_stop()
    c.disconnect()
    print("\n================ PASS-THROUGH TYPES ================", flush=True)
    npass = nfail = 0
    for name, ok, detail in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {name:26} {detail}", flush=True)
        npass += ok
        nfail += not ok
    print(f"\n========== {npass}/{npass + nfail} PASS ({'ALL PASS' if nfail == 0 else str(nfail) + ' FAIL'}) ==========", flush=True)
    sys.exit(0 if nfail == 0 else 1)


if __name__ == "__main__":
    main()
