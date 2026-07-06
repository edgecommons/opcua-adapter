package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalMatchingTest {

    private static SignalSpec spec(Integer namespace, String namespaceUri, String match) {
        JsonObject o = new JsonObject();
        if (namespace != null) {
            o.addProperty("namespace", namespace);
        }
        if (namespaceUri != null) {
            o.addProperty("namespaceUri", namespaceUri);
        }
        o.addProperty("match", match);
        return SignalSpec.fromJson(o);
    }

    // ---- resolveNamespace -------------------------------------------------------------------

    @Test
    void resolveNamespace_literalNamespace_returnsItDirectly() {
        SignalSpec s = spec(3, null, ".*");
        assertEquals(3, SignalMatching.resolveNamespace(s, new NamespaceTable()));
    }

    @Test
    void resolveNamespace_knownUri_returnsResolvedIndex() {
        NamespaceTable table = new NamespaceTable();
        var idx = table.add("urn:test:known");
        SignalSpec s = spec(null, "urn:test:known", ".*");
        assertEquals(idx.intValue(), SignalMatching.resolveNamespace(s, table));
    }

    @Test
    void resolveNamespace_unknownUri_returnsMinusOne() {
        SignalSpec s = spec(null, "urn:test:unknown", ".*");
        assertEquals(-1, SignalMatching.resolveNamespace(s, new NamespaceTable()));
    }

    // ---- matches ------------------------------------------------------------------------------

    @Test
    void matches_includeMatchesIdentifier() {
        SignalSpec s = spec(2, null, "^Foo.*");
        assertTrue(SignalMatching.matches(s, 2, "FooBar", "", "", 2, true));
    }

    @Test
    void matches_includeMatchesBrowseNameWhenIdentifierDoesNot() {
        SignalSpec s = spec(2, null, "^Foo.*");
        assertTrue(SignalMatching.matches(s, 2, "Xyz", "FooBar", "", 2, true));
    }

    @Test
    void matches_includeMatchesDisplayNameWhenIdentifierAndBrowseNameDoNot() {
        SignalSpec s = spec(2, null, "^Foo.*");
        assertTrue(SignalMatching.matches(s, 2, "Xyz", "Abc", "FooBar", 2, true));
    }

    @Test
    void matches_excludeNameOnlyMatch_mustNotMatch() {
        // exclude (matchNames=false): a match only against browseName/displayName must NOT count.
        SignalSpec s = spec(2, null, "^Foo.*");
        assertFalse(SignalMatching.matches(s, 2, "Xyz", "FooBar", "FooBar", 2, false));
    }

    @Test
    void matches_excludeIdentifierMatch_doesMatch() {
        SignalSpec s = spec(2, null, "^Foo.*");
        assertTrue(SignalMatching.matches(s, 2, "FooBar", "Xyz", "Xyz", 2, false));
    }

    @Test
    void matches_wrongNamespace_noMatch() {
        SignalSpec s = spec(2, null, ".*");
        assertFalse(SignalMatching.matches(s, 2, "Anything", "Anything", "Anything", 3, true));
    }

    @Test
    void matches_unresolvedNamespace_noMatch() {
        SignalSpec s = spec(2, null, ".*");
        assertFalse(SignalMatching.matches(s, -1, "Anything", "Anything", "Anything", 2, true));
    }

    // ---- firstMatch ---------------------------------------------------------------------------

    @Test
    void firstMatch_returnsFirstMatchingSpecInOrder() {
        SignalSpec first = spec(2, null, "^Foo.*");
        SignalSpec second = spec(2, null, "^Bar.*");
        List<SignalSpec> specs = List.of(first, second);
        Map<SignalSpec, Integer> ns = new HashMap<>();
        ns.put(first, 2);
        ns.put(second, 2);

        SignalSpec result = SignalMatching.firstMatch(specs, ns, "BarBaz", "", "", 2, true);
        assertNotNull(result);
        assertSame(second, result);
    }

    @Test
    void firstMatch_whenMultipleSpecsMatch_returnsTheFirstInListOrder() {
        // Both specs match "BarBaz": this is the only assertion that actually pins first-match-wins
        // (the config that a multiply-matched node inherits — samplingRate/queueSize/deadband/topic).
        SignalSpec first = spec(2, null, "^Bar.*");
        SignalSpec second = spec(2, null, ".*");
        List<SignalSpec> specs = List.of(first, second);
        Map<SignalSpec, Integer> ns = new HashMap<>();
        ns.put(first, 2);
        ns.put(second, 2);

        SignalSpec result = SignalMatching.firstMatch(specs, ns, "BarBaz", "", "", 2, true);
        assertSame(first, result);
    }

    @Test
    void firstMatch_noneMatch_returnsNull() {
        SignalSpec only = spec(2, null, "^Foo.*");
        Map<SignalSpec, Integer> ns = Map.of(only, 2);
        assertNull(SignalMatching.firstMatch(List.of(only), ns, "Zzz", "Zzz", "Zzz", 2, true));
    }
}
