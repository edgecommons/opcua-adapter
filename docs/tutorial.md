# Tutorial — Bridge a Simulated OPC UA Server

This tutorial takes you from nothing to a running adapter that publishes live OPC UA values onto a
message bus, and then has you read and write a signal through it. It uses a bundled simulator, so you
need no real hardware. Follow it top to bottom; every command is meant to be copy-pasted, and the
expected output is shown so you know you are on track.

By the end you will have seen a `SouthboundSignalUpdate` message, performed an on-demand read, and
written a value back to the server.

> This is a guided walkthrough — it makes a few choices for you and keeps explanation brief. For the
> *why* behind each step, see the [explanation](explanation.md); for variations, see the
> [how-to guides](how-to-guides.md).

## Prerequisites

- **Java 25** and **Maven** (to build the adapter).
- **Python 3.10+** with `pip install asyncua paho-mqtt` (the simulator and a small test client).
- An **MQTT broker**. We use EMQX in Docker.

Run everything from the repository root.

## Step 1 — Build the adapter

```bash
mvn -q clean package
```
You should get `target/OpcUaAdapter-1.0.0.jar`.

## Step 2 — Start a message broker

```bash
docker run -d --name emqx -p 1883:1883 emqx/emqx:latest
```
Any MQTT broker on `localhost:1883` works.

## Step 3 — Start the simulated OPC UA server

```bash
python validation/opcua_sim_server.py
```
Leave it running. It serves a few changing signals (`Sine1`, `Sine2`, `Counter`) and one writable signal
(`Setpoint`) on `opc.tcp://localhost:4840/`, and prints:
```
[sim] namespace index = 2
[sim] starting on opc.tcp://localhost:4840/ (nodes: Sine1, Sine2, Counter, Setpoint)
```

## Step 4 — Run the adapter

In another terminal, point the adapter at the simulator and the broker using the bundled config:

```bash
java -jar target/OpcUaAdapter-1.0.0.jar \
  --platform HOST --transport MQTT validation/messaging-local.json \
  -c FILE validation/config.json -t tutorial-thing
```
Watch for these lines — they mean the adapter connected, browsed the server, and subscribed:
```
[sim1] connected to opc.tcp://localhost:4840/ (policy=None)
[sim1] browse complete: 254 variable nodes
[sim1] subscription 'sines': 2 monitored item(s)
[sim1] device started
```

## Step 5 — Watch signal updates (the data plane)

In a third terminal, subscribe to the adapter's output. A short Python client keeps this dependency-free:

```bash
python - <<'PY'
import paho.mqtt.client as mqtt, json
c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
c.on_connect = lambda c,u,f,rc,p=None: c.subscribe("ecv1/+/+/+/data/#")
def on_msg(c,u,m):
    b = json.loads(m.payload)["body"]
    s = b["samples"][0]
    print(f'{b["signal"]["id"]:45} = {s["value"]:>10}  [{s["quality"]}]')
c.on_message = on_msg
c.connect("localhost", 1883); c.loop_forever()
PY
```
Within a second you will see a steady stream of updates, e.g.:
```
ns=2;s=Sine1                                  =     0.7071  [GOOD]
ns=2;s=Sine2                                  =     0.7071  [GOOD]
```
That is the **data plane**: each change becomes a `SouthboundSignalUpdate` on the UNS `data` class
(`ecv1/tutorial-thing/OpcUaAdapter/sim1/data/{signalPath}`). Leave this running to observe the next
steps. (Stop it with Ctrl-C when done.)

## Step 6 — Read a signal on demand

Reads are the `sb/read` command verb (request/reply). Send a request naming the target `instance` and
read `Counter` and `Setpoint`:

```bash
python - <<'PY'
import paho.mqtt.client as mqtt, json, time
c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
got = []
c.on_connect = lambda c,u,f,rc,p=None: c.subscribe("app/reply/1")
c.on_message = lambda c,u,m: got.append(json.loads(m.payload))
c.connect("localhost", 1883); c.loop_start()
req = {"header": {"name": "sb/read", "version": "1.0", "reply_to": "app/reply/1", "correlation_id": "1"},
       "body": {"instance": "sim1", "signals": [{"ns": 2, "signalId": "Counter"}, {"ns": 2, "signalId": "Setpoint"}]}}
c.publish("ecv1/tutorial-thing/OpcUaAdapter/main/cmd/sb/read", json.dumps(req)); time.sleep(2)
print(json.dumps(got[0]["body"], indent=2))
PY
```
The reply is `{ "ok": true, "result": { "id": "sim1", "reads": [ … ] } }` listing the two signals with
their current values.

## Step 7 — Write a signal

Set `Setpoint` to `42.5` with the `sb/write` verb. The bundled config allow-lists this signal
(`writes.allow: ["ns=2;s=Setpoint"]`), so the write is accepted:

```bash
python - <<'PY'
import paho.mqtt.client as mqtt, json
c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2); c.connect("localhost", 1883)
req = {"header": {"name": "sb/write", "version": "1.0"},
       "body": {"instance": "sim1", "writes": [{"ns": 2, "signalId": "Setpoint", "value": 42.5}]}}
c.publish("ecv1/tutorial-thing/OpcUaAdapter/main/cmd/sb/write", json.dumps(req)); c.loop()
PY
```
Re-run Step 6 and you will see `Setpoint` is now `42.5` — the value travelled bus → adapter → OPC UA
server. (Add a `reply_to`/`correlation_id` to the header, as in Step 6, to get the per-entry write
acknowledgment.)

## Step 8 — Clean up

Stop the adapter, simulator, and watcher with Ctrl-C, and remove the broker:
```bash
docker rm -f emqx
```

## What you did

You built and ran the adapter, watched it stream OPC UA values as `SouthboundSignalUpdate` messages
(the data plane), and used the command surface to read and write a signal. The whole interaction
happened over the bus — no OPC UA client code on your side.

There is an automated version of exactly this flow (plaintext and secure) in
[`validation/`](../validation/README.md).

## Next steps

- Make it secure: [How-to — Connect to a secured server](how-to-guides.md#connect-to-a-secured-server).
- Subscribe to your own signals: [How-to — Choose exactly which signals to publish](how-to-guides.md#choose-exactly-which-signals-to-publish).
- Understand the timing settings before you tune them: [Explanation — The timing pipeline](explanation.md#the-timing-pipeline-the-thing-most-worth-understanding).
