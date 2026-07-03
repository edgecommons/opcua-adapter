package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonObject;

/** Lightweight per-instance counters (reads/writes), surfaced in status replies and health metrics. */
public class ClientMetrics {

    private long totalReadCount = 0L;
    private long intervalReadCount = 0L;
    private long totalWriteCount = 0L;
    private long intervalWriteCount = 0L;
    private long readErrors = 0L;
    private long writeErrors = 0L;

    public synchronized void resetIntervals() {
        intervalReadCount = 0L;
        intervalWriteCount = 0L;
    }

    public synchronized void incrementReadMetrics() {
        totalReadCount++;
        intervalReadCount++;
    }

    public synchronized void incrementWriteMetrics() {
        totalWriteCount++;
        intervalWriteCount++;
    }

    public synchronized void incrementReadErrors() {
        readErrors++;
    }

    public synchronized void incrementWriteErrors() {
        writeErrors++;
    }

    public synchronized long getIntervalReadErrors() {
        long e = readErrors;
        readErrors = 0L;
        return e;
    }

    /** Returns and resets the write-error count accumulated since the last call (interval measure). */
    public synchronized long getIntervalWriteErrors() {
        long e = writeErrors;
        writeErrors = 0L;
        return e;
    }

    public synchronized JsonObject toJsonObject() {
        JsonObject retVal = new JsonObject();
        JsonObject readCounts = new JsonObject();
        readCounts.addProperty("interval", intervalReadCount);
        readCounts.addProperty("total", totalReadCount);
        JsonObject writeCounts = new JsonObject();
        writeCounts.addProperty("interval", intervalWriteCount);
        writeCounts.addProperty("total", totalWriteCount);
        retVal.add("read", readCounts);
        retVal.add("write", writeCounts);
        return retVal;
    }
}
