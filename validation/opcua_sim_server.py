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
    # Writable DateTime node -- KEP's Simulator can't host a Date tag, so DateTime write round-trip
    # is validated here instead.
    dtrw = await sim.add_variable(ua.NodeId("DateTimeRW", idx), ua.QualifiedName("DateTimeRW", idx),
                                  datetime(2020, 1, 1, tzinfo=timezone.utc), ua.VariantType.DateTime)
    await dtrw.set_writable(True)

    print("[sim] starting on opc.tcp://localhost:4840/ (nodes: Sine1, Sine2, Counter, Setpoint, DateTimeRW)", flush=True)
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
