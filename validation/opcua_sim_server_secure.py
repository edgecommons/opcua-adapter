"""Secured asyncua OPC UA sim server: Basic256Sha256 / SignAndEncrypt.

Loads its own cert/key, advertises a secure endpoint, and validates client certs as peers
(PEER_CLIENT|TIME_RANGE) WITHOUT requiring them in a trust store (fine for a smoke). Same address
space as the plaintext sim (Sine1/Sine2/Counter/Setpoint, ns=2).
"""
import asyncio
import math
import os

from asyncua import Server, ua
from asyncua.crypto.validator import CertificateValidator, CertificateValidatorOptions as O

# Certs live in ./certs next to this script (created by gen_certs.py).
SCRATCH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "certs")


async def main():
    server = Server()
    await server.init()
    server.set_endpoint("opc.tcp://localhost:4840/")
    server.set_server_name("GGCommons Secure Sim")
    try:
        await server.set_application_uri("urn:ggcommons:sim:server")
    except Exception as e:
        print(f"[sim] set_application_uri: {e}", flush=True)

    await server.load_certificate(f"{SCRATCH}/server_cert.pem")
    await server.load_private_key(f"{SCRATCH}/server_key.pem")
    server.set_security_policy([ua.SecurityPolicyType.Basic256Sha256_SignAndEncrypt])
    try:
        server.set_certificate_validator(CertificateValidator(O.PEER_CLIENT | O.TIME_RANGE))
        print("[sim] client-cert validation: PEER_CLIENT|TIME_RANGE (no trust-store requirement)", flush=True)
    except Exception as e:
        print(f"[sim] validator setup failed, relying on default: {e}", flush=True)

    idx = await server.register_namespace("urn:ggcommons:sim")
    print(f"[sim] namespace index = {idx}", flush=True)
    sim = await server.nodes.objects.add_folder(ua.NodeId("Simulation", idx), ua.QualifiedName("Simulation", idx))
    sine1 = await sim.add_variable(ua.NodeId("Sine1", idx), ua.QualifiedName("Sine1", idx), 0.0)
    sine2 = await sim.add_variable(ua.NodeId("Sine2", idx), ua.QualifiedName("Sine2", idx), 0.0)
    counter = await sim.add_variable(ua.NodeId("Counter", idx), ua.QualifiedName("Counter", idx), 0)
    setpoint = await sim.add_variable(ua.NodeId("Setpoint", idx), ua.QualifiedName("Setpoint", idx), 0.0)
    await setpoint.set_writable(True)

    print("[sim] SECURE server on opc.tcp://localhost:4840/ (Basic256Sha256/SignAndEncrypt)", flush=True)
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
