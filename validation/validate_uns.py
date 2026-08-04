"""Live smoke-validation of the OPC UA adapter on the CURRENT wire.

Speaks what the adapter actually publishes today — the `ecv1/...` UNS grammar with the protobuf
envelope, and the `cmd/sb/*` command family — rather than the retired `southbound/...` JSON shape.

Phases:
  A  subscribe -> SouthboundSignalUpdate on ecv1/.../data/<canonical channel token>
  B  identity   -> signal.id is the canonical nsu= form; channel token discriminates ns + idType
  C  sb/signals -> inventory reports canonical ids and the writable flag
  D  sb/write   -> an allow-listed target succeeds; a non-listed target is refused WRITE_NOT_ALLOWED
  E  sb/read    -> on-demand read round-trips; an over-cap request is refused BAD_ARGS
  F  sb/browse / sb/rescan / sb/status / pause / resume / repoll

Run from the repo root with the sim server and EMQX already up.
"""
import json
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, r"C:/Users/breis/source/edgecommons/core/libs/python")

import paho.mqtt.client as mqtt
from edgecommons.messaging.message import Message
from edgecommons.messaging.message_builder import MessageBuilder

BROKER_HOST, BROKER_PORT = "localhost", 1883
THING = "smoke-thing"
COMPONENT = "opcua-adapter"
INSTANCE = "sim1"

# From validation/config.json: hierarchy site/shop/line/device with identity lab/s1/l1.
DEVICE = THING
DATA_WILDCARD = "ecv1/+/+/+/data/#"
ANY_WILDCARD = "ecv1/#"   # a bare "#" is refused by the broker ACL

SIM_NS = "urn:edgecommons:sim"
SIM_TOKEN = "uea03203e"          # u + first 8 hex of SHA-256("urn:edgecommons:sim")

updates = []      # (topic, Message)
replies = {}      # correlation_id -> body dict
failures = []
passes = []


def check(name, ok, detail=""):
    (passes if ok else failures).append(name)
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def on_connect(client, userdata, flags, reason_code, properties=None):
    client.subscribe(ANY_WILDCARD)


def on_message(client, userdata, msg):
    try:
        m = Message.from_bytes(msg.payload)
        header = m.get_header()          # a MessageHeader value object, not a dict
    except Exception:
        return
    if getattr(header, "name", None) == "SouthboundSignalUpdate":
        updates.append((msg.topic, m))
        return
    # Only a message delivered on the reply topic is a reply. We also receive our own request back
    # (it carries the same correlation id), and it would otherwise win the race.
    if "/cmd/reply/" not in msg.topic:
        return
    cid = getattr(header, "correlation_id", None)
    if cid:
        try:
            replies[cid] = m.get_body()
        except Exception:
            replies[cid] = None


def request(client, verb, body, timeout=15.0):
    """Send a cmd/sb/* request and wait for its correlated reply."""
    cid = str(uuid.uuid4())
    reply_to = f"ecv1/{DEVICE}/{COMPONENT}/{INSTANCE}/cmd/reply/{cid}"
    topic = f"ecv1/{DEVICE}/{COMPONENT}/{INSTANCE}/cmd/{verb}"
    request_msg = (MessageBuilder(verb, "1.0")
                   .with_command(body)
                   .with_correlation_id(cid)
                   .with_reply_to(reply_to)
                   .build())
    client.subscribe(reply_to)
    client.publish(topic, request_msg.to_bytes())
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cid in replies:
            return replies.pop(cid)
        time.sleep(0.1)
    return None


def main():
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(BROKER_HOST, BROKER_PORT, 30)
    client.loop_start()

    print("\nPhase A — subscribe -> SouthboundSignalUpdate on the UNS data class")
    deadline = time.time() + 30
    while time.time() < deadline and len(updates) < 3:
        time.sleep(0.5)
    check("telemetry arrives on ecv1/.../data/", len(updates) > 0,
          f"{len(updates)} update(s); topics seen: "
          f"{sorted({t for t, _ in updates})[:3]}")
    if not updates:
        print("\nno telemetry — cannot continue")
        client.loop_stop()
        return 1

    topics = sorted({t for t, _ in updates})
    data_topics = [t for t in topics if "/data/" in t]
    check("topic uses the ecv1 root and the data class", bool(data_topics), str(topics[:2]))

    print("\nPhase B — canonical identity on the wire")
    channel = data_topics[0].rsplit("/data/", 1)[1] if data_topics else ""
    check("channel token carries the namespace discriminator",
          channel.startswith(SIM_TOKEN + "_"), f"channel={channel!r} expected prefix {SIM_TOKEN}_")
    check("channel token carries the identifier type",
          channel.startswith(SIM_TOKEN + "_s_"), f"channel={channel!r}")

    body = None
    for _, m in updates:
        b = m.get_body()
        if b:
            body = b
            break
    sig = (body or {}).get("signal", {})
    signal_id = sig.get("id", "")
    check("signal.id is the canonical nsu= form",
          signal_id.startswith(f"nsu={SIM_NS};"), f"signal.id={signal_id!r}")
    check("signal.id carries no namespace index",
          not signal_id.startswith("ns="), f"signal.id={signal_id!r}")
    addr = sig.get("address", {})
    check("signal.address still carries namespaceUri", addr.get("namespaceUri") == SIM_NS, str(addr))

    print("\nPhase C — sb/signals inventory")
    r = request(client, "sb/signals", {"instance": INSTANCE})
    ok = isinstance(r, dict) and r.get("ok") is True
    check("sb/signals answers", ok, str(r)[:160])
    if ok:
        signals = r["result"]["signals"]
        ids = [s["signalId"] for s in signals]
        check("inventory reports canonical ids",
              all(i.startswith("nsu=") or i.startswith("ns=0;") for i in ids), str(ids[:3]))
        setpoint = [s for s in signals if s["signalId"].endswith(";s=Setpoint")]
        check("the allow-listed signal is in the inventory", bool(setpoint), str(ids))
        check("the allow-listed signal reports writable=true",
              bool(setpoint) and setpoint[0]["writable"] is True, str(setpoint[:1]))
        non_writable = [s for s in signals if not s["writable"]]
        check("a non-allow-listed signal reports writable=false", bool(non_writable),
              f"{len(non_writable)} non-writable")

    print("\nPhase D — sb/write allow-list gate (canonical key)")
    r = request(client, "sb/write", {"instance": INSTANCE, "writes": [
        {"namespaceUri": SIM_NS, "signalId": "Setpoint", "value": 42.5}]})
    ok = isinstance(r, dict) and r.get("ok") is True
    check("sb/write answers for an allow-listed target", ok, str(r)[:200])
    if ok:
        entry = r["result"]["writes"][0]
        check("the allow-listed write is accepted", entry.get("status") == "SUCCESS", str(entry))

    r = request(client, "sb/write", {"instance": INSTANCE, "writes": [
        {"namespaceUri": SIM_NS, "signalId": "Counter", "value": 7}]})
    if isinstance(r, dict) and r.get("ok"):
        entry = r["result"]["writes"][0]
        check("a non-allow-listed write is refused",
              entry.get("status") == "FAILED" and "allow" in str(entry.get("message", "")),
              str(entry))

    print("\nPhase E — sb/read and the request cap")
    r = request(client, "sb/read", {"instance": INSTANCE, "signals": [
        {"namespaceUri": SIM_NS, "signalId": "Counter"}]})
    ok = isinstance(r, dict) and r.get("ok") is True
    check("sb/read round-trips", ok, str(r)[:200])
    if ok:
        reads = r["result"]["reads"]
        check("read result carries a canonical signal.id",
              reads and reads[0]["signal"]["id"].startswith("nsu="), str(reads[:1])[:160])

    many = [{"namespaceUri": SIM_NS, "signalId": f"Sine{i}"} for i in range(1200)]
    r = request(client, "sb/read", {"instance": INSTANCE, "signals": many})
    refused = isinstance(r, dict) and r.get("ok") is False and \
        r.get("error", {}).get("code") == "BAD_ARGS"
    check("an over-cap sb/read is refused with BAD_ARGS", refused, str(r)[:200])

    print("\nPhase F — remaining verbs")
    r = request(client, "sb/status", {"instance": INSTANCE})
    check("sb/status answers connected",
          isinstance(r, dict) and r.get("ok") and r["result"]["connected"] is True, str(r)[:160])

    r = request(client, "sb/browse", {"instance": INSTANCE, "depth": 2, "maxRefs": 50})
    check("sb/browse answers", isinstance(r, dict) and r.get("ok") is True, str(r)[:120])

    r = request(client, "sb/rescan", {"instance": INSTANCE})
    check("sb/rescan completes and reports rescanned=true",
          isinstance(r, dict) and r.get("ok") and r["result"].get("rescanned") is True, str(r)[:160])

    r = request(client, "sb/pause", {"instance": INSTANCE})
    paused_ok = isinstance(r, dict) and r.get("ok") and r["result"].get("paused") is True
    check("sb/pause answers", paused_ok, str(r)[:120])
    r = request(client, "repoll", {"instance": INSTANCE})
    check("repoll is refused while paused",
          isinstance(r, dict) and r.get("ok") is False and r.get("error", {}).get("code") == "PAUSED",
          str(r)[:160])
    r = request(client, "sb/resume", {"instance": INSTANCE})
    check("sb/resume answers", isinstance(r, dict) and r.get("ok"), str(r)[:120])

    r = request(client, "repoll", {"instance": INSTANCE})
    check("repoll answers after resume",
          isinstance(r, dict) and r.get("ok") and r["result"].get("polled", 0) > 0, str(r)[:160])

    client.loop_stop()
    print(f"\n{'=' * 60}\n{len(passes)} passed, {len(failures)} failed")
    if failures:
        for f in failures:
            print(f"  FAILED: {f}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
