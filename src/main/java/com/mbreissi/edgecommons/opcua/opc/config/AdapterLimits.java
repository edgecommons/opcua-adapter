package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonObject;

/**
 * The bounded-operation budgets read from {@code component.global.browse} and
 * {@code component.global.limits}.
 *
 * <p>Every unbounded path in the adapter — address-space browse, on-demand read/write batches, the
 * publish buffer, and the blocking service calls — draws its ceiling from here, so a caller-supplied
 * request or a pathological server cannot exhaust the adapter or the OPC UA server. Defaults are
 * generous enough that a conforming console never encounters them.
 */
public final class AdapterLimits {

    /** Address-space nodes visited in one browse before the traversal reports truncation. */
    public static final int DEFAULT_BROWSE_MAX_NODES = 50_000;
    /** Hierarchy depth visited in one browse. */
    public static final int DEFAULT_BROWSE_MAX_DEPTH = 32;
    /** Node ids accepted by one {@code sb/read} (explicit + include-matched, after dedup). */
    public static final int DEFAULT_MAX_READ_TARGETS = 1000;
    /** Entries accepted by one {@code sb/write} batch. */
    public static final int DEFAULT_MAX_WRITE_TARGETS = 1000;
    /** Deadline on a single blocking OPC UA service call. */
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 15_000L;
    /** Fallback per-service-call chunk when the server publishes no operation limits. */
    public static final int DEFAULT_CHUNK_SIZE = 100;
    /** Samples buffered per signal between batch flushes before the oldest are dropped. */
    public static final int DEFAULT_MAX_BUFFERED_SAMPLES = 10_000;
    /** Characters a caller-supplied matcher regex may contain. */
    public static final int DEFAULT_MAX_REGEX_LENGTH = 512;
    /** Character accesses one regex match may perform before it is abandoned. */
    public static final int DEFAULT_REGEX_STEP_BUDGET = 100_000;

    private final int browseMaxNodes;
    private final int browseMaxDepth;
    private final int maxReadTargets;
    private final int maxWriteTargets;
    private final long commandTimeoutMs;
    private final int chunkSize;
    private final int maxBufferedSamples;
    private final int maxRegexLength;
    private final int regexStepBudget;

    private AdapterLimits(int browseMaxNodes, int browseMaxDepth, int maxReadTargets, int maxWriteTargets,
                          long commandTimeoutMs, int chunkSize, int maxBufferedSamples,
                          int maxRegexLength, int regexStepBudget) {
        this.browseMaxNodes = browseMaxNodes;
        this.browseMaxDepth = browseMaxDepth;
        this.maxReadTargets = maxReadTargets;
        this.maxWriteTargets = maxWriteTargets;
        this.commandTimeoutMs = commandTimeoutMs;
        this.chunkSize = chunkSize;
        this.maxBufferedSamples = maxBufferedSamples;
        this.maxRegexLength = maxRegexLength;
        this.regexStepBudget = regexStepBudget;
    }

    /** The defaults, for tests and for a config with no {@code browse}/{@code limits} block. */
    public static AdapterLimits defaults() {
        return new AdapterLimits(DEFAULT_BROWSE_MAX_NODES, DEFAULT_BROWSE_MAX_DEPTH,
                DEFAULT_MAX_READ_TARGETS, DEFAULT_MAX_WRITE_TARGETS, DEFAULT_COMMAND_TIMEOUT_MS,
                DEFAULT_CHUNK_SIZE, DEFAULT_MAX_BUFFERED_SAMPLES,
                DEFAULT_MAX_REGEX_LENGTH, DEFAULT_REGEX_STEP_BUDGET);
    }

    /**
     * Read the budgets from {@code component.global}. Absent keys take their default; a
     * non-positive value is ignored in favour of the default rather than disabling the guard.
     */
    public static AdapterLimits fromGlobal(JsonObject globalConfig) {
        JsonObject browse = obj(globalConfig, "browse");
        JsonObject limits = obj(globalConfig, "limits");
        return new AdapterLimits(
                positiveInt(browse, "maxNodes", DEFAULT_BROWSE_MAX_NODES),
                positiveInt(browse, "maxDepth", DEFAULT_BROWSE_MAX_DEPTH),
                positiveInt(limits, "maxReadTargets", DEFAULT_MAX_READ_TARGETS),
                positiveInt(limits, "maxWriteTargets", DEFAULT_MAX_WRITE_TARGETS),
                positiveLong(limits, "commandTimeoutMs", DEFAULT_COMMAND_TIMEOUT_MS),
                positiveInt(limits, "chunkSize", DEFAULT_CHUNK_SIZE),
                positiveInt(limits, "maxBufferedSamples", DEFAULT_MAX_BUFFERED_SAMPLES),
                positiveInt(limits, "maxRegexLength", DEFAULT_MAX_REGEX_LENGTH),
                positiveInt(limits, "regexStepBudget", DEFAULT_REGEX_STEP_BUDGET));
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static int positiveInt(JsonObject o, String key, int dflt) {
        long v = positiveLong(o, key, dflt);
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    private static long positiveLong(JsonObject o, String key, long dflt) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) {
            return dflt;
        }
        try {
            long v = o.get(key).getAsLong();
            return v > 0 ? v : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    public int getBrowseMaxNodes() {
        return browseMaxNodes;
    }

    public int getBrowseMaxDepth() {
        return browseMaxDepth;
    }

    public int getMaxReadTargets() {
        return maxReadTargets;
    }

    public int getMaxWriteTargets() {
        return maxWriteTargets;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getMaxBufferedSamples() {
        return maxBufferedSamples;
    }

    public int getMaxRegexLength() {
        return maxRegexLength;
    }

    public int getRegexStepBudget() {
        return regexStepBudget;
    }
}
