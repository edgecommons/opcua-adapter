"""Fetch a live OPC UA server's instance certificate and save it as PEM (to pin in trust config).

The server certificate is carried in each secured endpoint's EndpointDescription, so this needs only
a bare GetEndpoints call (no session, no trust) -- handy for bootstrapping the client's trust store.

Usage:
    python validation/kep_server_cert.py opc.tcp://192.168.1.180:49320 validation/certs/kep_server_cert.pem
"""
import asyncio
import os
import sys

from asyncua import Client
from cryptography import x509
from cryptography.hazmat.primitives import serialization


async def main():
    if len(sys.argv) < 3:
        print("usage: kep_server_cert.py <opc.tcp url> <out.pem>")
        sys.exit(2)
    url, out = sys.argv[1], sys.argv[2]

    endpoints = await Client(url).connect_and_get_server_endpoints()
    der = None
    for ep in endpoints:
        if ep.ServerCertificate:
            der = ep.ServerCertificate
            if "#None" not in ep.SecurityPolicyUri:   # prefer a secured endpoint's cert
                break
    if not der:
        print("no server certificate found in any endpoint")
        sys.exit(1)

    cert = x509.load_der_x509_certificate(der)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    print(f"wrote {out}")
    print(f"  subject: {cert.subject.rfc4514_string()}")
    try:
        san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        print(f"  SAN: {[str(u) for u in san]}")
    except x509.ExtensionNotFound:
        print("  (no SubjectAltName)")


if __name__ == "__main__":
    asyncio.run(main())
