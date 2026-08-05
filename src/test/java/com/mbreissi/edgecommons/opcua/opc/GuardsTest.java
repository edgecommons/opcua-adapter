package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mbreissi.edgecommons.opcua.opc.config.AdapterLimits;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded-operation guards: security selection fails closed, caller regexes cannot run away, and
 * service-call batches are partitioned to the server's published limits.
 */
class GuardsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ---- SecurityPolicies ------------------------------------------------------------------------

    @Test
    void parsePolicy_acceptsMiloNames() {
        assertEquals(SecurityPolicy.None, SecurityPolicies.parsePolicy("None"));
        assertEquals(SecurityPolicy.Basic256Sha256, SecurityPolicies.parsePolicy("Basic256Sha256"));
    }

    /**
     * The fail-closed rule: a typo previously became {@code SecurityPolicy.None}, silently downgrading
     * an intended encrypted session to plaintext against any server exposing a {@code None} endpoint.
     */
    @Test
    void parsePolicy_refusesAnUnknownName_ratherThanDowngrading() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecurityPolicies.parsePolicy("Basic256Sha25"));
        assertTrue(e.getMessage().contains("invalid connection.securityPolicy"));
        assertTrue(e.getMessage().contains("Basic256Sha256"), "the message should list the valid values");
    }

    @Test
    void parsePolicy_refusesBlank() {
        assertThrows(IllegalArgumentException.class, () -> SecurityPolicies.parsePolicy(null));
        assertThrows(IllegalArgumentException.class, () -> SecurityPolicies.parsePolicy("  "));
    }

    @Test
    void parseMode_acceptsMiloNamesAndRefusesOthers() {
        assertEquals(MessageSecurityMode.SignAndEncrypt, SecurityPolicies.parseMode("SignAndEncrypt"));
        assertThrows(IllegalArgumentException.class, () -> SecurityPolicies.parseMode("Encrypt"));
    }

    @Test
    void checkCombination_acceptsCoherentPairs() {
        SecurityPolicies.checkCombination(SecurityPolicy.None, MessageSecurityMode.None);
        SecurityPolicies.checkCombination(SecurityPolicy.Basic256Sha256, MessageSecurityMode.SignAndEncrypt);
        SecurityPolicies.checkCombination(SecurityPolicy.Basic256Sha256, MessageSecurityMode.Sign);
    }

    /** A cipher suite with nothing signed or encrypted asks for security and then declines it. */
    @Test
    void checkCombination_refusesASecurePolicyWithoutAMode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecurityPolicies.checkCombination(SecurityPolicy.Basic256Sha256, MessageSecurityMode.None));
        assertTrue(e.getMessage().contains("requires connection.messageMode"));
    }

    @Test
    void checkCombination_refusesAModeWithoutAPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> SecurityPolicies.checkCombination(SecurityPolicy.None, MessageSecurityMode.Sign));
    }

    // ---- SafeRegex -------------------------------------------------------------------------------

    @Test
    void compile_acceptsOrdinaryPatterns() {
        assertTrue(SafeRegex.compile("^Sim\\.Sine.*", 512).matcher("Sim.Sine1").matches());
    }

    @Test
    void compile_refusesAnOverlongPattern() {
        String huge = "a".repeat(600);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SafeRegex.compile(huge, 512));
        assertTrue(e.getMessage().contains("the limit is 512"));
    }

    @Test
    void compile_refusesInvalidSyntax() {
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("(unclosed", 512));
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile(null, 512));
    }

    @Test
    void matches_behavesLikePatternMatchesForOrdinaryInput() {
        Pattern p = Pattern.compile("[A-Z]+\\d+");
        assertTrue(SafeRegex.matches(p, "ABC123", 100_000));
        assertFalse(SafeRegex.matches(p, "abc123", 100_000));
        assertFalse(SafeRegex.matches(p, null, 100_000));
    }

    /**
     * A caller-supplied pattern is evaluated against every cached node, so a pattern whose matching
     * work grows super-linearly must not be allowed to monopolise the command thread. Nested
     * quantifiers over a non-matching input are the realistic shape: {@code (x+x+)+y} costs roughly
     * quadratically in the input length, and the budget caps it.
     */
    @Test
    void matches_abandonsPatternsThatExceedTheirWorkBudget() {
        Pattern expensive = Pattern.compile("(x+x+)+y");
        String input = "x".repeat(24);
        assertThrows(SafeRegex.BudgetExceededException.class,
                () -> SafeRegex.matches(expensive, input, 1_000));
    }

    /** The same pattern completes when the budget is generous — the guard is a cap, not a ban. */
    @Test
    void matches_allowsTheSameWorkUnderAGenerousBudget() {
        assertFalse(SafeRegex.matches(Pattern.compile("(x+x+)+y"), "x".repeat(24), 1_000_000));
    }

    @Test
    void matches_withNoBudget_fallsBackToPlainMatching() {
        assertTrue(SafeRegex.matches(Pattern.compile("a+"), "aaa", 0));
    }

    // ---- Batching --------------------------------------------------------------------------------

    @Test
    void partition_splitsPreservingOrder() {
        List<List<Integer>> chunks = Batching.partition(List.of(1, 2, 3, 4, 5), 2);
        assertEquals(3, chunks.size());
        assertEquals(List.of(1, 2), chunks.get(0));
        assertEquals(List.of(5), chunks.get(2));
    }

    @Test
    void partition_edgeCases() {
        assertTrue(Batching.partition(List.of(), 10).isEmpty());
        assertTrue(Batching.partition(null, 10).isEmpty());
        assertEquals(1, Batching.partition(List.of(1, 2), 10).size());
        assertEquals(1, Batching.partition(List.of(1, 2), 0).size());
    }

    @Test
    void effectiveChunk_prefersTheSmallerOfServerLimitAndConfig() {
        assertEquals(50, Batching.effectiveChunk(50, 100));
        assertEquals(100, Batching.effectiveChunk(500, 100));
        assertEquals(100, Batching.effectiveChunk(0, 100), "no server limit falls back to config");
        assertEquals(50, Batching.effectiveChunk(50, 0));
    }

    // ---- AdapterLimits ---------------------------------------------------------------------------

    @Test
    void limits_defaultWhenUnconfigured() {
        AdapterLimits limits = AdapterLimits.fromGlobal(new JsonObject());
        assertEquals(AdapterLimits.DEFAULT_BROWSE_MAX_NODES, limits.getBrowseMaxNodes());
        assertEquals(AdapterLimits.DEFAULT_MAX_READ_TARGETS, limits.getMaxReadTargets());
        assertEquals(AdapterLimits.DEFAULT_COMMAND_TIMEOUT_MS, limits.getCommandTimeoutMs());
        assertEquals(AdapterLimits.DEFAULT_CHUNK_SIZE, limits.getChunkSize());
    }

    @Test
    void limits_readFromGlobalConfig() {
        AdapterLimits limits = AdapterLimits.fromGlobal(json(
                "{\"browse\":{\"maxNodes\":10,\"maxDepth\":3},"
                        + "\"limits\":{\"maxReadTargets\":25,\"maxWriteTargets\":7,"
                        + "\"commandTimeoutMs\":900,\"chunkSize\":5,\"maxBufferedSamples\":64}}"));
        assertEquals(10, limits.getBrowseMaxNodes());
        assertEquals(3, limits.getBrowseMaxDepth());
        assertEquals(25, limits.getMaxReadTargets());
        assertEquals(7, limits.getMaxWriteTargets());
        assertEquals(900L, limits.getCommandTimeoutMs());
        assertEquals(5, limits.getChunkSize());
        assertEquals(64, limits.getMaxBufferedSamples());
    }

    /** A non-positive budget would disable the guard, so it is ignored in favour of the default. */
    @Test
    void limits_ignoreNonPositiveAndMalformedValues() {
        AdapterLimits limits = AdapterLimits.fromGlobal(json(
                "{\"limits\":{\"maxReadTargets\":0,\"chunkSize\":-4,\"commandTimeoutMs\":\"soon\"}}"));
        assertEquals(AdapterLimits.DEFAULT_MAX_READ_TARGETS, limits.getMaxReadTargets());
        assertEquals(AdapterLimits.DEFAULT_CHUNK_SIZE, limits.getChunkSize());
        assertEquals(AdapterLimits.DEFAULT_COMMAND_TIMEOUT_MS, limits.getCommandTimeoutMs());
    }

    @Test
    void limits_toleratesAbsentAndNullSections() {
        AdapterLimits limits = AdapterLimits.fromGlobal(null);
        assertEquals(AdapterLimits.DEFAULT_BROWSE_MAX_DEPTH, limits.getBrowseMaxDepth());
    }
}
