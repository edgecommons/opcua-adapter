package com.mbreissi.edgecommons.opcua.opc;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a batch of service-call targets into chunks no larger than the server's published operation
 * limit ({@code MaxNodesPerRead} / {@code MaxNodesPerWrite}, OPC UA Part 5 §6.3.11).
 *
 * <p>A server advertises those limits precisely so a client does not hand it an unbounded batch; the
 * read/write/repoll paths partition through here and concatenate the per-chunk results, so reply
 * ordering matches request ordering exactly.
 */
public final class Batching {

    private Batching() {
    }

    /**
     * Partition into consecutive sublists of at most {@code limit} elements, preserving order. A
     * non-positive {@code limit} means "no partitioning" and yields a single chunk.
     */
    public static <T> List<List<T>> partition(List<T> items, int limit) {
        List<List<T>> chunks = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return chunks;
        }
        if (limit <= 0 || items.size() <= limit) {
            chunks.add(new ArrayList<>(items));
            return chunks;
        }
        for (int start = 0; start < items.size(); start += limit) {
            chunks.add(new ArrayList<>(items.subList(start, Math.min(start + limit, items.size()))));
        }
        return chunks;
    }

    /**
     * The effective chunk size: the server's published operation limit when it is positive and
     * smaller than the configured fallback, else the fallback.
     */
    public static int effectiveChunk(int serverLimit, int configuredChunk) {
        int fallback = configuredChunk > 0 ? configuredChunk : Integer.MAX_VALUE;
        if (serverLimit <= 0) {
            return fallback;
        }
        return Math.min(serverLimit, fallback);
    }
}
