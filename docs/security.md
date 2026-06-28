# Secure Connections

The adapter supports authenticated, encrypted OPC UA channels (e.g. `Basic256Sha256` /
`SignAndEncrypt`). Security is configured per instance under `connection`. This document covers the
options, the certificate sources, server trust, and — importantly — the **OPC UA certificate
requirements** that trip up most first attempts.

## Enabling security

```jsonc
"connection": {
  "endpoint": "opc.tcp://host:4840/",
  "securityPolicy": "Basic256Sha256",     // None disables security (default)
  "messageMode": "SignAndEncrypt",         // None | Sign | SignAndEncrypt
  "applicationUri": null,                   // optional; see "Application URI" below
  "clientCertificate": { /* the adapter's identity — one source below */ },
  "trust": { /* how the server cert is trusted */ }
}
```
The adapter selects the server endpoint matching **both** the policy and the message mode. For a
secure policy, `messageMode: "None"` is auto-upgraded to `SignAndEncrypt`.

## Client certificate (`clientCertificate`)

The adapter presents an **application instance certificate + private key**. Choose one source:

### `vault` (recommended)
Reads a `TlsBundle` secret from the ggcommons encrypted credentials vault. Requires a `credentials`
config section.
```jsonc
"clientCertificate": { "source": "vault", "secret": "opcua/kep1/appcert" }
```
The secret is canonical JSON `{ "certPem": "...", "keyPem": "...", "caPem": "..." }`. `certPem`+`keyPem`
are the client identity; `caPem` (optional) is used as the server trust anchor.

### `file`
PEM files on disk:
```jsonc
"clientCertificate": { "source": "file", "certPath": "/etc/opcua/client_cert.pem", "keyPath": "/etc/opcua/client_key.pem" }
```

### `pkcs11` (HSM-resident; net-new, validate before production)
Key + cert on a PKCS#11 token (non-extractable key; signing on the token):
```jsonc
"clientCertificate": {
  "source": "pkcs11", "modulePath": "/usr/lib/softhsm/libsofthsm2.so",
  "slotIndex": 0, "pinEnv": "HSM_PIN", "keyLabel": "opcua-key", "certLabel": "opcua-cert"
}
```
`pin` (literal) or `pinEnv` (env var name). This path is new and untested without a token — verify
against your HSM/SoftHSM.

## Server trust (`trust`)

```jsonc
"trust": {
  "pkiDir": "/var/lib/opcua/{InstanceId}/pki",
  "serverCertificate": { "source": "file", "path": "/etc/opcua/server_cert.pem" }
}
```

| Key | Meaning |
|-----|---------|
| `pkiDir` | Directory backing the trust list. The adapter creates `trusted/`, `rejected/`, `issuers/` here. Server certs not trusted are written to `rejected/` for inspection. |
| `serverCertificate` | Optionally **pin** the server cert as trusted: `{source:"file", path}` or `{source:"vault", secret, field:"caPem"}` (or rely on the `vault` client bundle's `caPem`). |

Trust is explicit — there is **no auto-trust** mode. Either pin the server cert via
`serverCertificate`, or drop it into `pkiDir/trusted/certs/`.

## Application URI

OPC UA requires the client's `applicationUri` to **byte-for-byte equal** the URI in its certificate's
SubjectAltName. The adapter derives `applicationUri` from the client cert's SAN URI automatically; set
`connection.applicationUri` only to override. A mismatch is the single most common secure-connection
failure (the server accepts the channel then rejects the session).

## OPC UA certificate requirements

Application instance certificates must satisfy OPC UA's profile, and Milo's validator enforces it:

- **KeyUsage** must include: `digitalSignature`, `nonRepudiation` (contentCommitment),
  `keyEncipherment`, `dataEncipherment`.
- **Self-signed** certs (their own trust anchor) must **also** set `keyCertSign` and
  `BasicConstraints: CA = true`.
- A **SubjectAltName URI** carrying the application URI (matching `applicationUri`).

A non-compliant cert fails the handshake with `Bad_CertificateUseNotAllowed: required KeyUsage '…'`.
See `validation/gen_certs.py` for a compliant self-signed generator (client + server).

## Vault provisioning

For `source: "vault"`, the `TlsBundle` secret must exist before the adapter connects. Seed it via the
ggcommons credentials tooling / central sync (AWS Secrets Manager over TES), or out-of-band, as
canonical JSON:
```json
{ "certPem": "-----BEGIN CERTIFICATE-----\n…", "keyPem": "-----BEGIN PRIVATE KEY-----\n…", "caPem": "-----BEGIN CERTIFICATE-----\n…" }
```

## Full secure example

```jsonc
"instances": [ {
  "id": "kep1",
  "connection": {
    "endpoint": "opc.tcp://192.168.1.50:49320/",
    "securityPolicy": "Basic256Sha256",
    "messageMode": "SignAndEncrypt",
    "clientCertificate": { "source": "vault", "secret": "opcua/kep1/appcert" },
    "trust": {
      "pkiDir": "/var/lib/opcua/{InstanceId}/pki",
      "serverCertificate": { "source": "vault", "secret": "opcua/kep1/appcert", "field": "caPem" }
    }
  },
  "subscriptions": [ /* … */ ]
} ]
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Bad_CertificateUseNotAllowed: required KeyUsage 'nonRepudiation'` | Client/server cert missing `nonRepudiation` — regenerate per the requirements above. |
| `Bad_CertificateUseNotAllowed: required KeyUsage 'keyCertSign'` | Self-signed cert lacks `keyCertSign`/`CA=true`. |
| Channel opens then session is rejected | `applicationUri` ≠ client cert SAN URI. |
| Secure connect retries forever, server cert in `pkiDir/rejected/` | Server cert not trusted — pin it via `trust.serverCertificate`. |
| `no credentials subsystem configured … cert source is 'vault'` | Add a `credentials` config section, or use the `file` source. |
