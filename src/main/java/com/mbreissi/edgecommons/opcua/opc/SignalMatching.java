package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure signal-matching core shared by the subscription path ({@link SubscriptionManager}) and the
 * on-demand read include/exclude path ({@link CommandService}): resolves a {@link SignalSpec}'s
 * namespace to the server's current index, and matches a node's identifier / browse name / display
 * name against a spec's regex within that namespace. Stateless — no Milo client, no logging.
 */
public final class SignalMatching {

    private SignalMatching() {
    }

    /**
     * Resolve a spec's namespace to an effective index: a {@code namespaceUri} (preferred) is looked
     * up in {@code table} — {@code -1} if not found; otherwise the literal {@code namespace} index is
     * used.
     */
    public static int resolveNamespace(SignalSpec spec, NamespaceTable table) {
        if (spec.getNamespaceUri() != null) {
            UShort idx = table.getIndex(spec.getNamespaceUri());
            return idx != null ? idx.intValue() : -1;
        }
        return spec.getNamespace();
    }

    /**
     * Match one node (already reduced to its identifier/browseName/displayName/namespace index)
     * against a single spec's resolved namespace + regex. An include matcher tests all three
     * ({@code matchNames} true); an exclude matcher tests the identifier only ({@code matchNames}
     * false).
     */
    public static boolean matches(SignalSpec spec, int resolvedNs, String idStr, String browseName,
                                  String displayName, int nodeNs, boolean matchNames) {
        if (resolvedNs < 0 || nodeNs != resolvedNs) {
            return false;
        }
        Pattern rx = spec.pattern();
        return rx.matcher(idStr).matches()
                || (matchNames && (rx.matcher(browseName).matches() || rx.matcher(displayName).matches()));
    }

    /** The first spec (in list order) that matches the node, or {@code null} if none do. */
    public static SignalSpec firstMatch(List<SignalSpec> specs, Map<SignalSpec, Integer> nsBySpec,
                                        String idStr, String browseName, String displayName,
                                        int nodeNs, boolean matchNames) {
        for (SignalSpec spec : specs) {
            int ns = nsBySpec.getOrDefault(spec, -1);
            if (matches(spec, ns, idStr, browseName, displayName, nodeNs, matchNames)) {
                return spec;
            }
        }
        return null;
    }
}
