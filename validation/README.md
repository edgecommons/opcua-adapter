# OPC UA validation support

Use the [current tutorial](../docs/tutorial.md) for the supported simulator flow: start
`opcua_sim_server.py`, run the adapter with `config.json` and `messaging-local.json`, decode
protobuf `SouthboundSignalUpdate` messages, then exercise `sb/read` and `sb/write` with
`ec-uns-cmd`. The target instance is `sim1`; the writable simulator signal is `ns=2;s=Setpoint`.

```bash
pip install asyncua paho-mqtt
mvn verify
python validation/opcua_sim_server.py
```

The simulator is a separate long-running process. `mvn verify` validates the local Java suite
and its coverage gate; it does not establish live server, TLS or Greengrass interoperability.
For secure server setup and trust material, use the maintained
[security how-to](../docs/how-to-guides.md#connect-to-a-secured-server).

## Legacy clients and historical evidence

The JSON-envelope MQTT clients in this directory predate the current protobuf wire and
CommandsRegistry reply contract. Their old read/write/control instructions and ALL PASS claims
are historical scenario references, not current conformance gates. Repair and independently
validate their protobuf decoding, topic scope and `{ok, result|error}` assertions before reusing
them as automated acceptance tools. Simulator, certificate and server fixtures remain useful
inputs; their presence alone is not evidence that a current live gate passed.

The main checkout and [open remediation PR #16](https://github.com/edgecommons/opcua-adapter/pull/16)
are distinct baselines. As reviewed on 2026-09-06, that PR remains pending; its implementation and
worktree evidence are not shipped-main guarantees. The runtime log tracked on that branch is
pending cleanup when remediation resumes.

Cross-repository validation is owned by the Dallas
[bottling-company-test](https://github.com/edgecommons/bottling-company-test) harness. Live secure
OPC UA and Greengrass IPC validation require their respective server and lab infrastructure.
