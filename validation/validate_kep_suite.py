"""Comprehensive integration suite for the OPC UA adapter against a live KEPServerEX.

Prereqs:
  1. python validation/kep_setup.py        (creates the GGCommonsTest channel/tags; needs admin creds)
  2. adapter running with validation/config-kep-suite.json (over EMQX on localhost:1883)

Then: python validation/validate_kep_suite.py

Covers, against real KEP tags (ns=2 "Kepware Server", id "GGCommonsTest.Device1.<name>"):
  - data-type mapping on subscribe (Boolean/SByte/Byte/Int16/UInt16/Int32/UInt32/Int64/UInt64/Float/Double/String)
  - changing values from simulator functions (RAMP/SINE/USER)
  - include/exclude filtering and per-tag topic override
  - batch write -> batch read round-trip for every writable type
  - addressing by namespaceUri vs literal ns index
  - error handling (unknown tag omitted; bad namespaceUri tolerated)
  - control plane (status, subscriptions) + health metric + quality normalization
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
PREFIX = "GGCommonsTest.Device1."
NS = "Kepware Server"

TYPES = ["Boolean", "SByte", "Byte", "Int16", "UInt16", "Int32", "UInt32",
         "Int64", "UInt64", "Float", "Double", "String"]


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


TYPE_CHECK = {
    "Boolean": lambda v: isinstance(v, bool),
    "SByte": is_int, "Byte": is_int, "Int16": is_int, "UInt16": is_int,
    "Int32": is_int, "UInt32": is_int, "Int64": is_int, "UInt64": is_int,
    "Float": is_num, "Double": is_num,
    "String": lambda v: isinstance(v, str),
}

WRITE = {
    "Boolean": True, "SByte": -5, "Byte": 200, "Int16": -1234, "UInt16": 50000,
    "Int32": -100000, "UInt32": 3000000000, "Int64": -5000000000, "UInt64": 10000000000,
    "Float": 12.5, "Double": 1234.5, "String": "ggcommons-suite",
}

msgs = []   # (topic, payload)
checks = []  # (section, name, ok, detail)


def check(section, name, ok, detail=""):
    checks.append((section, name, bool(ok), detail))


def on_connect(c, u, f, rc, p=None):
    # This EMQX does not honor the bare "#" wildcard; subscribe by prefix.
    for t in ("southbound/#", "ggtest/#", "metrics/#"):
        c.subscribe(t)


def on_message(c, u, msg):
    try:
        msgs.append((msg.topic, json.loads(msg.payload.decode())))
    except Exception:
        pass


def updates():
    return [(t, p) for t, p in msgs if p.get("header", {}).get("name") == "SouthboundTagUpdate"]


def node_of(p):
    return p.get("body", {}).get("tag", {}).get("address", {}).get("nodeId")


def samples_for(name):
    """All (topic, sample) seen for a tag, across the run."""
    out = []
    for t, p in updates():
        if node_of(p) == PREFIX + name:
            for s in p.get("body", {}).get("samples", []):
                out.append((t, s))
    return out


def latest(name):
    s = samples_for(name)
    return s[-1] if s else None


def envelope(name, body, reply_to=None):
    h = {"name": name, "version": "1.0", "timestamp": datetime.now(timezone.utc).isoformat(),
         "uuid": str(uuid.uuid4()), "correlation_id": str(uuid.uuid4())}
    if reply_to:
        h["reply_to"] = reply_to
    return h["correlation_id"], json.dumps({"header": h, "tags": {}, "body": body})


def request(c, topic, name, body, timeout=5):
    cid = str(uuid.uuid4())
    reply = f"southbound/reply/suite/{cid}"
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


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="kep-suite")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    # --- wait for the adapter to connect + publish a spread of the new tags ---------------
    print("[*] waiting up to 45s for GGCommonsTest updates...", flush=True)
    deadline = time.time() + 45
    while time.time() < deadline:
        seen = {node_of(p) for _, p in updates() if (node_of(p) or "").startswith(PREFIX)}
        if len(seen) >= 8:
            break
        time.sleep(0.5)
    seen = {node_of(p) for _, p in updates() if (node_of(p) or "").startswith(PREFIX)}
    if len(seen) < 4:
        print(f"[!] only {len(seen)} GGCommonsTest tags seen; is the adapter running on config-kep-suite.json?", flush=True)
        summarize()
        return

    # derive component/instance from a default-topic update (not the LiveSine override topic)
    comp = inst = None
    for t, p in updates():
        parts = t.split("/")
        if t.startswith("southbound/") and len(parts) >= 5 and parts[4].startswith("GGCommonsTest"):
            comp, inst = parts[2], parts[3]
            break
    if not comp:
        print("[!] could not derive component/instance from topics", flush=True)
        summarize()
        return
    read_topic = f"southbound/{comp}/{inst}/read"
    write_topic = f"southbound/{comp}/{inst}/write"
    print(f"[*] component={comp} instance={inst}", flush=True)

    # --- subscribe stream active (the live simulator tags keep publishing) ----------------
    check("subscribe", "tag-update stream active", len(updates()) > 0, f"{len(updates())} updates collected")

    # --- T1: data-type mapping (on-demand read of every supported type) -------------------
    rp = request(c, read_topic, "ReadTags", {"tags": [{"namespaceUri": NS, "tagId": PREFIX + n} for n in TYPES]})
    t1 = {node_of_read(e): e for e in (rp.get("body", {}).get("reads", []) if rp else [])}
    for name in TYPES:
        e = t1.get(PREFIX + name)
        if not e:
            check("types/read", name, False, "missing from read")
            continue
        v, q, addr = e.get("value"), e.get("quality"), e.get("tag", {}).get("address", {})
        ok = TYPE_CHECK[name](v) and q == "GOOD" and addr.get("namespaceUri") == NS and addr.get("ns") is not None
        check("types/read", name, ok, f"value={v!r} q={q} ns={addr.get('ns')}")

    # --- T2/T3/T4: batch write every writable type, then batch read back ------------------
    writes = [{"namespaceUri": NS, "tagId": PREFIX + n, "value": WRITE[n]} for n in TYPES]
    _, w = envelope("WriteTags", {"writes": writes})
    c.publish(write_topic, w)
    time.sleep(2.5)
    rp = request(c, read_topic, "ReadTags",
                 {"tags": [{"namespaceUri": NS, "tagId": PREFIX + n} for n in TYPES]})
    got = {}
    if rp:
        for e in rp.get("body", {}).get("reads", []):
            got[node_of_read(e)] = e
    check("batch", "batch-read replied", rp is not None, f"{len(got)} entries")
    for name in TYPES:
        e = got.get(PREFIX + name)
        exp = WRITE[name]
        if not e:
            check("types/write", name, False, "missing from read-back")
            continue
        gv = e.get("value")
        if isinstance(exp, bool):
            ok = gv == exp
        elif isinstance(exp, str):
            ok = gv == exp
        elif isinstance(exp, float):
            ok = gv is not None and abs(float(gv) - exp) < max(1e-4, abs(exp) * 1e-5)
        else:
            ok = gv is not None and int(gv) == exp
        check("types/write", name, ok, f"wrote {exp!r} -> read {gv!r} ({e.get('quality')})")

    # --- T6: addressing by namespaceUri vs literal ns index ------------------------------
    rp = request(c, read_topic, "ReadTags", {"tags": [
        {"namespaceUri": NS, "tagId": PREFIX + "Int32"},
        {"ns": 2, "tagId": PREFIX + "Int32"},
    ]})
    vals = [e.get("value") for e in (rp.get("body", {}).get("reads", []) if rp else [])]
    check("addressing", "namespaceUri == ns index", len(vals) == 2 and vals[0] == vals[1], f"{vals}")

    # --- T7: error handling --------------------------------------------------------------
    # unresolvable namespaceUri -> omitted from reads; non-existent node (valid ns) -> present, non-GOOD
    rp = request(c, read_topic, "ReadTags", {"tags": [
        {"namespaceUri": NS, "tagId": PREFIX + "Int32"},
        {"namespaceUri": "urn:bogus:ns", "tagId": PREFIX + "Int32"},
        {"namespaceUri": NS, "tagId": PREFIX + "DoesNotExist"},
    ]})
    reads = rp.get("body", {}).get("reads", []) if rp else []
    entries = {node_of_read(e): e for e in reads}
    valid = entries.get(PREFIX + "Int32")
    missing = entries.get(PREFIX + "DoesNotExist")
    check("errors", "unresolvable namespaceUri omitted", len(reads) == 2, f"{len(reads)} entries")
    check("errors", "valid read GOOD", bool(valid) and valid.get("quality") == "GOOD")
    check("errors", "non-existent node -> non-GOOD",
          bool(missing) and missing.get("quality") != "GOOD", f"q={missing.get('quality') if missing else None}")
    # bad namespaceUri on a write must not crash the adapter
    _, bw = envelope("WriteTags", {"writes": [{"namespaceUri": "urn:bogus:ns", "tagId": "X", "value": 1}]})
    c.publish(write_topic, bw)
    time.sleep(1)
    rp = request(c, read_topic, "ReadTags", {"tags": [{"namespaceUri": NS, "tagId": PREFIX + "Int32"}]})
    check("errors", "adapter alive after bad write", rp is not None and len(rp.get("body", {}).get("reads", [])) == 1)

    # --- give live values time to accumulate, then T2 changing / filter / topic ----------
    while time.time() < deadline:  # (deadline already passed; this is a no-op guard)
        break
    time.sleep(6)

    for name in ["LiveRamp", "LiveBool"]:
        vals = {json.dumps(s.get("value")) for _, s in samples_for(name)}
        check("live", f"{name} changes", len(vals) >= 2, f"{len(vals)} distinct values")
    sine = samples_for("LiveSine")
    sine_vals = {json.dumps(s.get("value")) for _, s in sine}
    check("live", "LiveSine changes", len(sine_vals) >= 2, f"{len(sine_vals)} distinct values")

    # filter: LiveRandom is excluded -> never published
    check("filter", "LiveRandom excluded", not samples_for("LiveRandom"), f"{len(samples_for('LiveRandom'))} seen")
    # per-tag topic override: LiveSine routed to ggtest/live/*
    check("filter", "LiveSine topic override",
          bool(sine) and all(t.startswith("ggtest/live/") for t, _ in sine), f"topics={sorted({t for t,_ in sine})}")

    # --- T8: control plane ---------------------------------------------------------------
    st = request(c, f"southbound/{comp}/{inst}/control/status", "status", {})
    sb = st.get("body", {}) if st else {}
    check("control", "status connected+metrics", bool(sb.get("connected")) and "metrics" in sb, f"{sb.get('connected')}")
    subq = request(c, f"southbound/{comp}/{inst}/control/subscriptions", "subscriptions", {})
    stags = subq.get("body", {}).get("tags", []) if subq else []
    names = {t.get("tagId") for t in stags}
    has_uri = all(t.get("namespaceUri") == NS for t in stags) and len(stags) > 0
    check("control", "subscriptions list + URIs", len(names) >= 8 and has_uri, f"{len(names)} tags")
    check("control", "excluded tag not subscribed", PREFIX + "LiveRandom" not in names)

    # --- T9: health metric + quality -----------------------------------------------------
    check("health", "southbound_health metric emitted",
          any(t.startswith("metrics/") for t, _ in msgs), "")
    live_pairs = sum([samples_for(n) for n in ("LiveRamp", "LiveSine", "LiveBool")], [])
    quals = {s.get("quality") for _, s in live_pairs}
    rawok = len(live_pairs) > 0 and all(s.get("qualityRaw") for _, s in live_pairs)
    check("quality", "normalized GOOD + qualityRaw", quals == {"GOOD"} and rawok, f"{quals}")

    c.loop_stop()
    c.disconnect()
    summarize()


def node_of_read(e):
    return e.get("tag", {}).get("address", {}).get("nodeId")


def summarize():
    print("\n================ INTEGRATION SUITE RESULTS ================", flush=True)
    section = None
    npass = nfail = 0
    for sec, name, ok, detail in checks:
        if sec != section:
            print(f"\n[{sec}]", flush=True)
            section = sec
        print(f"  {'PASS' if ok else 'FAIL'}  {name:32} {detail}", flush=True)
        npass += ok
        nfail += not ok
    total = npass + nfail
    print(f"\n========== {npass}/{total} PASS ({'ALL PASS' if nfail == 0 else str(nfail) + ' FAIL'}) ==========", flush=True)
    sys.exit(0 if nfail == 0 and total > 0 else 1)


if __name__ == "__main__":
    main()
