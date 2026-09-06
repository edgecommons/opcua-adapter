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
- **Python 3.10+** with `pip install asyncua paho-mqtt` for the simulator and MQTT client. In this organization workspace, install the matching decoder with `pip install -e ../core/libs/python`.
- [ec-uns-cmd](https://github.com/edgecommons/ec-uns-cmd) on `PATH` for bounded protobuf request/reply.
- An **MQTT broker**. We use EMQX in Docker.

Run everything from the repository root. Python here-documents use a Bash-compatible shell; in
PowerShell, save the Python contents to a `.py` file and run `python <file>.py`.

## Step 1 — Build the adapter

```bash
mvn -q clean package
```
You should get `target/opcua-adapter-1.0.0.jar`.

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
java -jar target/opcua-adapter-1.0.0.jar \
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

The bus carries protobuf bytes. In a third terminal, decode the simulator instance's updates to a
human-readable JSON projection:

```bash
python - <<'PY'
import json
import paho.mqtt.client as mqtt
from edgecommons.messaging.message import Message

c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
c.on_connect = lambda c, u, f, rc, p: c.subscribe("ecv1/tutorial-thing/opcua-adapter/sim1/data/#", qos=1)
def on_message(c, u, m):
    message = Message.from_bytes(m.payload)
    print(m.topic, json.dumps(message.to_diagnostic_json(), indent=2))
c.on_message = on_message
c.connect("localhost", 1883)
try:
    c.loop_forever()
except KeyboardInterrupt:
    pass
finally:
    c.unsubscribe("ecv1/tutorial-thing/opcua-adapter/sim1/data/#")
    c.disconnect()
PY
```

Look for `SouthboundSignalUpdate` messages containing `body.signal` and `body.samples`, including
the changing `Counter` and sine signals. Stop the watcher with Ctrl-C when finished.

## Step 6 — Read a signal on demand

Select `sim1` on the command topic and read the two signals. `--body` is a native JSON argument
object; the tool encodes the protobuf envelope and awaits the correlated reply.

```bash
ec-uns-cmd --broker localhost:1883 --device tutorial-thing --component opcua-adapter --instance sim1 sb/read --body '{"signals":[{"ns":2,"signalId":"Counter"},{"ns":2,"signalId":"Setpoint"}]}'
```

The tool prints the reply's `result`, containing `id: "sim1"` and per-signal `reads` outcomes.

## Step 7 — Write a signal

The bundled configuration allows writes to `ns=2;s=Setpoint`:

```bash
ec-uns-cmd --broker localhost:1883 --device tutorial-thing --component opcua-adapter --instance sim1 sb/write --body '{"writes":[{"ns":2,"signalId":"Setpoint","value":42.5}]}'
```

Check the per-entry write acknowledgment, then repeat Step 6 and check that `Setpoint` is `42.5`.

## Step 8 — Clean up

Stop the adapter, simulator, and watcher with Ctrl-C, and remove the broker:
```bash
docker rm -f emqx
```

## What you did

You built and ran the adapter, watched it stream OPC UA values as `SouthboundSignalUpdate` messages
(the data plane), and used the command surface to read and write a signal. The whole interaction
happened over the bus — no OPC UA client code on your side.

The [validation guide](../validation/README.md) distinguishes the usable simulator and fixtures
from older JSON-wire clients that require repair before they can serve as current release gates.

## Next steps

- Make it secure: [How-to — Connect to a secured server](how-to-guides.md#connect-to-a-secured-server).
- Subscribe to your own signals: [How-to — Choose exactly which signals to publish](how-to-guides.md#choose-exactly-which-signals-to-publish).
- Understand the timing settings before you tune them: [Explanation — The timing pipeline](explanation.md#the-timing-pipeline-the-thing-most-worth-understanding).
