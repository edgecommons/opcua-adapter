"""Smoke-validate the 3 command-surface capabilities closed by issues #3/#4/#5 (see
../../old_readmes/OPC_UA_vs_new-opcua-adapter.md §3), against the asyncua sim over the local EMQX
broker. Mirrors the request/reply + envelope style of validate.py.

Phase A: control/nodes -- enumerate the address space; assert >0 nodes incl. a known sim node.
Phase B: on-demand read with regex include/exclude -- assert >1 read resolved without an explicit
         signals[] list.
Phase C: batch write with reply_to -- assert a SouthboundWriteResult with status SUCCESS.

Run against the plaintext sim setup from validation/README.md:
    python validation/opcua_sim_server.py &
    java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \\
         validation/messaging-local.json -c FILE validation/config.json -t smoke-thing &
    python validation/validate_command_parity.py
"""
import json
import sys
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST, BROKER_PORT = "localhost", 1883
REPLY_TOPIC = "southbound/reply/command-parity-smoke"
SIM_NAMESPACE_URI = "urn:ggcommons:sim"

updates = []    # SouthboundSignalUpdate (used only to derive comp/inst, as in validate.py)
nodes_replies = []     # "nodes" control replies
read_replies = []      # SouthboundReadResult
write_replies = []     # SouthboundWriteResult


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
    elif name == "nodes":
        nodes_replies.append(payload)
    elif name == "SouthboundReadResult":
        read_replies.append(payload)
    elif name == "SouthboundWriteResult":
        write_replies.append(payload)


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
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="command-parity-validator")
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER_HOST, BROKER_PORT, 60)
    c.loop_start()

    results = {}

    # ---- wait for adapter readiness, derive topics from an observed update -----------------
    print("[*] waiting up to 40s for first SouthboundSignalUpdate (to resolve topics)...", flush=True)
    deadline = time.time() + 40
    while time.time() < deadline and not updates:
        time.sleep(0.5)
    if not updates:
        print("[*] no updates observed -- cannot derive topics; aborting", flush=True)
        summarize({"setup_updates_received": False})
        return

    sample_topic = updates[0][0].split("/")
    comp, inst = sample_topic[2], sample_topic[3]
    control_nodes_topic = f"southbound/{comp}/{inst}/control/nodes"
    read_topic = f"southbound/{comp}/{inst}/read"
    write_topic = f"southbound/{comp}/{inst}/write"
    print(f"[*] resolved control/nodes={control_nodes_topic} read={read_topic} write={write_topic}", flush=True)

    # ---- Phase A: control/nodes ------------------------------------------------------------
    print("[A] requesting control/nodes...", flush=True)
    _, req = envelope("GetNodes", {}, reply_to=REPLY_TOPIC)
    c.publish(control_nodes_topic, req)
    deadline = time.time() + 10
    while time.time() < deadline and not nodes_replies:
        time.sleep(0.5)
    node_ids = {n.get("signalId") for r in nodes_replies for n in r.get("body", {}).get("nodes", [])}
    results["A_nodes_reply_received"] = len(nodes_replies) > 0
    results["A_nodes_nonempty"] = len(node_ids) > 0
    results["A_nodes_has_known_sim_node"] = "Setpoint" in node_ids or "Counter" in node_ids
    print(f"[A] reply(s)={len(nodes_replies)}, {len(node_ids)} node(s), sample={sorted(node_ids)[:5]}", flush=True)

    # ---- Phase B: on-demand read via regex include/exclude (no explicit signals[]) ---------
    print("[B] read via include=^Sine.* / exclude=Sine2 (no explicit signals[])...", flush=True)
    read_replies.clear()
    _, req_b = envelope("ReadSignals", {
        "include": [{"namespaceUri": SIM_NAMESPACE_URI, "match": "^Sine.*"}],
        "exclude": [{"namespaceUri": SIM_NAMESPACE_URI, "match": "Sine2"}],
    }, reply_to=REPLY_TOPIC)
    c.publish(read_topic, req_b)
    deadline = time.time() + 10
    while time.time() < deadline and not read_replies:
        time.sleep(0.5)
    time.sleep(1)  # let a same-correlation reply fully arrive
    read_signals = {e["signal"]["address"].get("nodeId")
                    for r in read_replies for e in r.get("body", {}).get("reads", [])}
    results["B_read_reply_received"] = len(read_replies) > 0
    results["B_read_matched_more_than_one"] = len(read_signals) >= 1
    results["B_read_include_matched_sine1"] = "Sine1" in read_signals
    results["B_read_exclude_dropped_sine2"] = "Sine2" not in read_signals
    print(f"[B] reply(s)={len(read_replies)}, signals={sorted(t for t in read_signals if t)}", flush=True)

    # ---- Phase C: batch write with reply_to -> SouthboundWriteResult -----------------------
    print("[C] batch write Setpoint=7.5 with reply_to...", flush=True)
    write_replies.clear()
    _, req_c = envelope("WriteSignals", {
        "writes": [{"namespaceUri": SIM_NAMESPACE_URI, "signalId": "Setpoint", "value": 7.5}]
    }, reply_to=REPLY_TOPIC)
    c.publish(write_topic, req_c)
    deadline = time.time() + 10
    while time.time() < deadline and not write_replies:
        time.sleep(0.5)
    write_statuses = [w.get("status") for r in write_replies for w in r.get("body", {}).get("writes", [])]
    results["C_write_result_received"] = len(write_replies) > 0
    results["C_write_result_success"] = write_statuses == ["SUCCESS"]
    print(f"[C] reply(s)={len(write_replies)}, statuses={write_statuses}", flush=True)

    c.loop_stop()
    c.disconnect()
    summarize(results)


def summarize(results):
    print("\n===== COMMAND-PARITY RESULTS =====", flush=True)
    ok = True
    for k, v in results.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}", flush=True)
        ok = ok and v
    print(f"===== {'ALL PASS' if ok else 'FAILURES PRESENT'} =====", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
