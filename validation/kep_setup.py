"""Create the GGCommonsTest channel/device/tags on KEPServerEX via the Configuration REST API.

Idempotent: deletes the channel first, then recreates it. Credentials come from the environment so
no secret is committed:

    KEP_API_HOST   default 192.168.1.180:57512
    KEP_API_USER   a Config-API account with project-modification permission (e.g. Administrator)
    KEP_API_PASS   that account's password

    KEP_API_USER=Administrator KEP_API_PASS=*** python validation/kep_setup.py

Creates (driver=Simulator, 8-Bit device so Byte/Char are available):
  - 12 read/write register tags, one per adapter-supported scalar type (round-trip write tests)
  - 4 read-only simulator-function tags with changing values (subscribe tests)

OPC UA exposes each as ns=2 ("Kepware Server"), identifier "GGCommonsTest.Device1.<name>".
"""
import base64
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

HOST = os.environ.get("KEP_API_HOST", "192.168.1.180:57512")
USER = os.environ.get("KEP_API_USER")
PASS = os.environ.get("KEP_API_PASS")
BASE = f"https://{HOST}/config/v1"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

CHANNEL = "GGCommonsTest"
DEVICE = "Device1"

# (name, address, TAG_DATA_TYPE)  -- read/write HOLDING registers (8-Bit device K-register map).
# K registers hold written values (good for write round-trips); the R registers in the Simulator
# free-run, so they are not used here.
TYPE_TAGS = [
    ("Boolean", "K0100.00", 1),
    ("SByte",   "K0300",    2),
    ("Byte",    "K0200",    3),
    ("Int16",   "K0800",    4),
    ("UInt16",  "K0900",    5),
    ("Int32",   "K0700",    6),
    ("UInt32",  "K0500",    7),
    ("Int64",   "K1200",   13),
    ("UInt64",  "K1100",   14),
    ("Float",   "K0600",    8),
    ("Double",  "K0400",    9),
    ("String",  "S0001",    0),
]

# (name, address, TAG_DATA_TYPE)  -- read-only simulator functions (auto-updating values)
LIVE_TAGS = [
    ("LiveRamp",   "RAMP (250, 0, 1000, 1)",                       6),
    ("LiveSine",   "SINE (10, -40.000000, 40.000000, 0.050000, 0)", 8),
    ("LiveRandom", "RANDOM (100, 0, 1000)",                        5),
    ("LiveBool",   "USER (1000,1,0)",                              1),
]


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    auth = "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    try:
        r = urllib.request.urlopen(req, context=CTX, timeout=20)
        txt = r.read().decode()
        return r.status, (json.loads(txt) if txt.strip() else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:
        return None, repr(e)


def tag_body(name, addr, dtype, rw):
    return {
        "common.ALLTYPES_NAME": name,
        "servermain.TAG_ADDRESS": addr,
        "servermain.TAG_DATA_TYPE": dtype,
        "servermain.TAG_READ_WRITE_ACCESS": rw,  # 0=read-only, 1=read/write
        "servermain.TAG_SCAN_RATE_MILLISECONDS": 100,
    }


def upsert(path_parent, name, body, child="tags"):
    """Create the child object, or edit it in place if it already exists (the Config-API account may
    have add+edit but not delete). Returns (status, action)."""
    st, resp = api("POST", f"{path_parent}/{child}", body)
    if st in (200, 201):
        return st, "created"
    # already exists -> GET, patch the fields we manage, PUT back
    enc = urllib.parse.quote(name)
    gst, cur = api("GET", f"{path_parent}/{child}/{enc}")
    if gst != 200 or not isinstance(cur, dict):
        return st, f"create failed ({resp})"
    cur.update(body)
    pst, presp = api("PUT", f"{path_parent}/{child}/{enc}", cur)
    return pst, ("updated" if pst in (200, 201) else f"update failed ({presp})")


def main():
    if not USER or not PASS:
        print("ERROR: set KEP_API_USER and KEP_API_PASS in the environment", file=sys.stderr)
        sys.exit(2)

    ch = urllib.parse.quote(CHANNEL)
    dev = urllib.parse.quote(DEVICE)

    # Idempotent without delete (the account may lack the Config-API delete permission): create the
    # channel/device if absent, then upsert every tag so re-runs converge to this definition.
    st, _ = api("POST", "/project/channels",
                {"common.ALLTYPES_NAME": CHANNEL, "servermain.MULTIPLE_TYPES_DEVICE_DRIVER": "Simulator"})
    print(f"channel  {CHANNEL}: [{st}] {'created' if st in (200, 201) else 'exists/keep'}")

    st, _ = api("POST", f"/project/channels/{ch}/devices",
                {"common.ALLTYPES_NAME": DEVICE, "servermain.MULTIPLE_TYPES_DEVICE_DRIVER": "Simulator",
                 "servermain.DEVICE_MODEL": 1})  # 1 = 8-Bit (exposes Byte/Char)
    print(f"device   {DEVICE} (8-Bit): [{st}] {'created' if st in (200, 201) else 'exists/keep'}")

    parent = f"/project/channels/{ch}/devices/{dev}"
    fail = 0
    for name, addr, dtype in TYPE_TAGS:
        st, action = upsert(parent, name, tag_body(name, addr, dtype, 1))
        print(f"  tag {name:8} {addr:12} dtype={dtype:<2} rw=1 -> [{st}] {action}")
        fail += st not in (200, 201)
    for name, addr, dtype in LIVE_TAGS:
        st, action = upsert(parent, name, tag_body(name, addr, dtype, 0))
        print(f"  tag {name:10} {addr:42} dtype={dtype:<2} rw=0 -> [{st}] {action}")
        fail += st not in (200, 201)

    st, tags = api("GET", f"{parent}/tags")
    n = len(tags) if isinstance(tags, list) else "?"
    print(f"\n{fail} failure(s); server reports {n} tag(s) on {CHANNEL}.{DEVICE}")
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
