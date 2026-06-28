"""Generate self-signed client + server certs (PEM) with SubjectAltName URIs for the OPC UA secure smoke.

client SAN URI = urn:ggcommons:opcua:adapter  (must match the adapter's derived applicationUri)
server SAN URI = urn:ggcommons:sim:server     (matches the asyncua server's application URI)
"""
import datetime
import os

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

# Certs are written to ./certs next to this script (gitignored).
SCRATCH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "certs")
os.makedirs(SCRATCH, exist_ok=True)


def gen(cn, uri, base):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName([x509.UniformResourceIdentifier(uri), x509.DNSName("localhost")]), critical=False)
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(digital_signature=True, content_commitment=True, key_encipherment=True,
                          data_encipherment=True, key_agreement=False, key_cert_sign=True,
                          crl_sign=True, encipher_only=False, decipher_only=False),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH, ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(key, hashes.SHA256())
    )
    with open(f"{base}_cert.pem", "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    with open(f"{base}_key.pem", "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    print(f"wrote {base}_cert.pem / {base}_key.pem (SAN URI {uri})")


gen("GGCommons OPC UA Adapter", "urn:ggcommons:opcua:adapter", f"{SCRATCH}/client")
gen("GGCommons Smoke Sim", "urn:ggcommons:sim:server", f"{SCRATCH}/server")
