package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * A stable OPC UA signal identity: {@code {namespaceUri, idType, identifier}}.
 *
 * <p>A namespace <b>index</b> is a per-session handle — a server may renumber its namespace table
 * between sessions (OPC UA Part 3 §5.2.2), so {@code ns=2;s=Tag} names a different node after a
 * restart that reorders namespaces. The southbound contract requires {@code signal.id} to be
 * "canonical and stable" (SOUTHBOUND.md §2), and an index-bearing id is neither. This type is the
 * stable form: the namespace <b>URI</b>, which the server guarantees, plus the identifier and its
 * type.
 *
 * <p>Three surfaces key on it, and each was previously index-bound:
 * <ul>
 *   <li>the {@code writes.allow[]} authorization gate — an index there can refuse a legitimate write
 *       or, worse, authorize a different node that has taken over the old index;</li>
 *   <li>the UNS {@code data} channel token, via {@link #channelToken()};</li>
 *   <li>the subscription inventory and staleness tracker.</li>
 * </ul>
 *
 * <p>Namespace 0 is a deliberate exception: {@code http://opcfoundation.org/UA/} is fixed by the
 * specification at index 0 and can never be renumbered, so it keeps the compact literal {@code ns=0;…}
 * form rather than carrying a URI that adds nothing.
 */
public record CanonicalSignalId(String namespaceUri, char idType, String identifier) {

    /** The OPC UA namespace-0 URI, fixed by the specification at index 0. */
    public static final String NS0_URI = "http://opcfoundation.org/UA/";
    /** The channel token used for namespace 0, in place of a hash. */
    public static final String NS0_TOKEN = "ns0";

    public CanonicalSignalId {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier is required");
        }
        if (idType != 'i' && idType != 's' && idType != 'g' && idType != 'b') {
            throw new IllegalArgumentException("idType must be one of i, s, g, b (got '" + idType + "')");
        }
    }

    /** Whether this identity lives in namespace 0. */
    public boolean isNamespaceZero() {
        return namespaceUri == null || NS0_URI.equals(namespaceUri);
    }

    /**
     * Build the canonical identity for a node id, resolving its namespace index against the server's
     * live namespace table.
     *
     * @throws IllegalArgumentException when the index is not present in the table (the server did not
     *                                  declare a URI for it, so no stable identity exists)
     */
    public static CanonicalSignalId of(NodeId nodeId, NamespaceTable table) {
        int ns = nodeId.getNamespaceIndex().intValue();
        char type = idTypeOf(nodeId);
        String identifier = nodeId.getIdentifier().toString();
        if (ns == 0) {
            return new CanonicalSignalId(null, type, identifier);
        }
        String uri = table != null ? table.get(ns) : null;
        if (uri == null) {
            throw new IllegalArgumentException("namespace index " + ns
                    + " is not in the server's namespace table; no stable identity can be formed for " + nodeId);
        }
        return new CanonicalSignalId(uri, type, identifier);
    }

    /** The single-letter OPC UA identifier type of a node id. */
    public static char idTypeOf(NodeId nodeId) {
        Object id = nodeId.getIdentifier();
        if (id instanceof UInteger) {
            return 'i';
        }
        if (id instanceof UUID) {
            return 'g';
        }
        if (id instanceof ByteString) {
            return 'b';
        }
        return 's';
    }

    /**
     * Resolve back to a live {@link NodeId} against the current namespace table, or {@code null} when
     * the server does not currently expose this namespace.
     */
    public NodeId toNodeId(NamespaceTable table) {
        int ns = 0;
        if (!isNamespaceZero()) {
            UShort index = table != null ? table.getIndex(namespaceUri) : null;
            if (index == null) {
                return null;
            }
            ns = index.intValue();
        }
        return switch (idType) {
            case 'i' -> new NodeId(ns, Unsigned.uint(Long.parseLong(identifier)));
            case 'g' -> new NodeId(ns, UUID.fromString(identifier));
            case 'b' -> new NodeId(ns, ByteString.of(identifier.getBytes(StandardCharsets.UTF_8)));
            default -> new NodeId(ns, identifier);
        };
    }

    /**
     * The canonical wire form — {@code nsu=<uri>;<type>=<id>}, or {@code ns=0;<type>=<id>} in
     * namespace 0. This is the published {@code signal.id} and the {@code writes.allow[]} key.
     */
    @Override
    public String toString() {
        if (isNamespaceZero()) {
            return "ns=0;" + idType + "=" + identifier;
        }
        return "nsu=" + namespaceUri + ";" + idType + "=" + identifier;
    }

    /**
     * Parse the canonical form. An {@code ns=<index>;…} form with a non-zero index is <b>rejected</b>:
     * a namespace index carries no stable meaning, so accepting one in an allow-list would reintroduce
     * exactly the ambiguity this type exists to remove.
     *
     * @throws IllegalArgumentException when the text is not a canonical id
     */
    public static CanonicalSignalId parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a signal id is required");
        }
        String value = text.trim();
        int semi = value.indexOf(';');
        if (semi < 0) {
            throw new IllegalArgumentException("malformed signal id '" + text
                    + "'; expected nsu=<namespaceUri>;<i|s|g|b>=<identifier>");
        }
        String nsPart = value.substring(0, semi);
        String idPart = value.substring(semi + 1);
        if (idPart.length() < 3 || idPart.charAt(1) != '=') {
            throw new IllegalArgumentException("malformed signal id '" + text
                    + "'; expected an <i|s|g|b>=<identifier> component");
        }
        char type = idPart.charAt(0);
        String identifier = idPart.substring(2);

        if (nsPart.startsWith("nsu=")) {
            String uri = nsPart.substring(4);
            if (uri.isBlank()) {
                throw new IllegalArgumentException("malformed signal id '" + text + "'; nsu= is empty");
            }
            return new CanonicalSignalId(uri, type, identifier);
        }
        if (nsPart.equals("ns=0")) {
            return new CanonicalSignalId(null, type, identifier);
        }
        if (nsPart.startsWith("ns=")) {
            throw new IllegalArgumentException("signal id '" + text
                    + "' uses a namespace index, which is not stable across sessions; use"
                    + " nsu=<namespaceUri>;" + type + "=" + identifier + " instead");
        }
        throw new IllegalArgumentException("malformed signal id '" + text
                + "'; expected nsu=<namespaceUri>;<i|s|g|b>=<identifier>");
    }

    /**
     * The single UNS channel token for this signal: {@code <nsToken>_<idType>_<identifier>}.
     *
     * <p>The namespace discriminator prevents two namespaces publishing the same identifier onto one
     * topic, and the id-type infix separates {@code i=42} from {@code s=42}. The token is a hash rather
     * than the URI itself because a namespace URI is whatever the vendor chose to call itself — it may
     * contain spaces or {@code //} (KEPServerEX registers the plain name {@code "Kepware Server"}), and
     * embedding that verbatim would put arbitrary vendor text into a topic level.
     *
     * <p>The caller still passes the result through the config sanitizer; the identifier portion can
     * legally contain characters the UNS token rule forbids.
     */
    public String channelToken() {
        return namespaceToken() + "_" + idType + "_" + identifier;
    }

    /** The namespace discriminator: {@code ns0}, or {@code u} + the first 8 hex of SHA-256(uri). */
    public String namespaceToken() {
        if (isNamespaceZero()) {
            return NS0_TOKEN;
        }
        return "u" + sha256Prefix(namespaceUri);
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
