"""Discover a live OPC UA server's endpoints, namespaces, and a sample of its tags.

Run this against KEPServerEX (or any server) to learn exactly how to configure the adapter
instead of guessing. It does two things:

  1. GetEndpoints over a bare secure channel (NO session) -- always works once TCP is open,
     even on a secured server with anonymous/None disabled. Lists every endpoint's security
     policy + message mode + accepted user-token types.
  2. If a None + Anonymous endpoint is reachable, opens a session and dumps the namespace
     array and a sample of variable nodes (NodeId + the stable namespace URI) so you can copy
     real `namespaceUri` / `tagId` values straight into config.json.

Usage:
    python validation/kep_discover.py opc.tcp://192.168.1.180:49320
    python validation/kep_discover.py opc.tcp://192.168.1.180:49320 --browse-depth 3 --max-tags 40
"""
import argparse
import asyncio

from asyncua import Client, ua


async def list_endpoints(url):
    print(f"\n=== Endpoints offered by {url} ===")
    client = Client(url)
    try:
        endpoints = await client.connect_and_get_server_endpoints()
    except Exception as e:
        print(f"  FAILED to reach server: {e!r}")
        return False
    for ep in endpoints:
        policy = ep.SecurityPolicyUri.rsplit("#", 1)[-1]
        mode = ep.SecurityMode.name if hasattr(ep.SecurityMode, "name") else ep.SecurityMode
        tokens = ",".join(sorted({t.TokenType.name for t in (ep.UserIdentityTokens or [])})) or "?"
        print(f"  {ep.EndpointUrl}")
        print(f"      policy={policy:<16} mode={mode:<14} userTokens={tokens}")
    return True


async def browse_sample(url, depth, max_tags):
    print(f"\n=== Trying a None+Anonymous session on {url} (for namespace + tag discovery) ===")
    client = Client(url)
    try:
        async with client:
            ns_array = await client.get_namespace_array()
            print("  NamespaceArray:")
            for i, uri in enumerate(ns_array):
                print(f"      [{i}] {uri}")

            print(f"\n  Sample variable nodes (depth {depth}, up to {max_tags}):")
            found = []

            async def walk(node, level):
                if len(found) >= max_tags or level > depth:
                    return
                for child in await node.get_children():
                    if len(found) >= max_tags:
                        return
                    nclass = await child.read_node_class()
                    nid = child.nodeid
                    if nclass == ua.NodeClass.Variable:
                        uri = ns_array[nid.NamespaceIndex] if nid.NamespaceIndex < len(ns_array) else "?"
                        ident = nid.Identifier
                        found.append((nid.NamespaceIndex, uri, ident))
                        print(f"      ns={nid.NamespaceIndex} uri={uri!r} tagId={ident!r}")
                    elif nclass == ua.NodeClass.Object:
                        await walk(child, level + 1)

            await walk(client.nodes.objects, 0)
            if not found:
                print("      (no variable nodes found within depth -- raise --browse-depth)")
    except Exception as e:
        print(f"  Could not open a None+Anonymous session: {e!r}")
        print("  (Expected if the server only exposes secured endpoints or disallows anonymous.)")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url", help="opc.tcp://host:port")
    ap.add_argument("--browse-depth", type=int, default=3)
    ap.add_argument("--max-tags", type=int, default=40)
    args = ap.parse_args()

    reachable = await list_endpoints(args.url)
    if reachable:
        await browse_sample(args.url, args.browse_depth, args.max_tags)


if __name__ == "__main__":
    asyncio.run(main())
