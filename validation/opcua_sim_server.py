"""Tiny asyncua OPC UA simulation server for adapter smoke testing.

Anonymous / SecurityPolicy=None. Address space (ns=2):
  Simulation/
    Sine1   (Double, changing)
    Sine2   (Double, changing)
    Counter (Int,    changing)
    Setpoint(Double, WRITABLE) -- for the batch-write test
Endpoint: opc.tcp://localhost:4840/
"""
import asyncio
import math
import uuid
from datetime import datetime, timezone

from asyncua import Server, ua


async def main():
    server = Server()
    await server.init()
    server.set_endpoint("opc.tcp://localhost:4840/")
    server.set_server_name("GGCommons Smoke Sim")
    # Allow anonymous + no security (matches the adapter's None policy for this smoke).
    server.set_security_policy([ua.SecurityPolicyType.NoSecurity])

    idx = await server.register_namespace("urn:ggcommons:sim")
    print(f"[sim] namespace index = {idx}", flush=True)

    sim = await server.nodes.objects.add_folder(ua.NodeId("Simulation", idx),
                                                ua.QualifiedName("Simulation", idx))
    sine1 = await sim.add_variable(ua.NodeId("Sine1", idx), ua.QualifiedName("Sine1", idx), 0.0)
    sine2 = await sim.add_variable(ua.NodeId("Sine2", idx), ua.QualifiedName("Sine2", idx), 0.0)
    counter = await sim.add_variable(ua.NodeId("Counter", idx), ua.QualifiedName("Counter", idx), 0)
    setpoint = await sim.add_variable(ua.NodeId("Setpoint", idx), ua.QualifiedName("Setpoint", idx), 0.0)
    await setpoint.set_writable(True)
    # Writable DateTime node -- KEP's Simulator can't host a Date signal, so DateTime write round-trip
    # is validated here instead.
    dtrw = await sim.add_variable(ua.NodeId("DateTimeRW", idx), ua.QualifiedName("DateTimeRW", idx),
                                  datetime(2020, 1, 1, tzinfo=timezone.utc), ua.VariantType.DateTime)
    await dtrw.set_writable(True)

    # Pass-through types: the contract has no JSON model for these, so the adapter renders them as a
    # string on read and rejects writes. Made writable here so a *skipped* write is observable
    # (the value stays unchanged). Folder: Types/.
    types = await server.nodes.objects.add_folder(ua.NodeId("Types", idx), ua.QualifiedName("Types", idx))
    passthrough = [
        ("ByteStringNode", b"\x01\x02\x03\x04", ua.VariantType.ByteString),
        ("GuidNode", uuid.UUID("12345678-1234-5678-1234-567812345678"), ua.VariantType.Guid),
        ("NodeIdNode", ua.NodeId("SomeTag", idx), ua.VariantType.NodeId),
        ("LocalizedTextNode", ua.LocalizedText("hello"), ua.VariantType.LocalizedText),
        ("QualifiedNameNode", ua.QualifiedName("qn", idx), ua.VariantType.QualifiedName),
        ("StatusCodeNode", ua.StatusCode(0), ua.VariantType.StatusCode),
        ("XmlElementNode", ua.XmlElement("<a>x</a>"), ua.VariantType.XmlElement),
    ]
    for name, val, vt in passthrough:
        node = await types.add_variable(ua.NodeId(name, idx), ua.QualifiedName(name, idx), val, vt)
        await node.set_writable(True)

    print("[sim] starting on opc.tcp://localhost:4840/ "
          "(nodes: Sine1, Sine2, Counter, Setpoint, DateTimeRW, Types/*)", flush=True)
    async with server:
        i = 0
        while True:
            await sine1.write_value(round(math.sin(i / 10.0), 4))
            await sine2.write_value(round(math.cos(i / 10.0), 4))
            await counter.write_value(i)
            i += 1
            await asyncio.sleep(0.5)


if __name__ == "__main__":
    asyncio.run(main())
