package com.micewriter.sandbox.loadtest;

import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class LoadTestRun {

    public enum Status { RUNNING, DONE, STOPPED, FAILED }

    public static final class CellExecution {
        public final int rate;
        public final int payloadSizeBytes;
        public final int durationSec;
        public final String payload;
        public final Instant queuedAt;
        public final LongAdder sent = new LongAdder();
        public final LongAdder failed = new LongAdder();
        public volatile Instant actualStartedAt;
        public volatile Instant endedAt;
        public volatile Timer timer;
        public volatile double p50LatMs;
        public volatile double p95LatMs;
        public volatile double p99LatMs;

        public CellExecution(int rate, int payloadSizeBytes, int durationSec, String payload, Instant queuedAt) {
            this.rate = rate;
            this.payloadSizeBytes = payloadSizeBytes;
            this.durationSec = durationSec;
            this.payload = payload;
            this.queuedAt = queuedAt;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rate", rate);
            m.put("payloadSizeBytes", payloadSizeBytes);
            m.put("durationSec", durationSec);
            m.put("queuedAt", queuedAt.toString());
            m.put("startedAt", actualStartedAt != null ? actualStartedAt.toString() : null);
            m.put("endedAt", endedAt != null ? endedAt.toString() : null);
            long sentNow = sent.sum();
            long failedNow = failed.sum();
            Instant startRef = actualStartedAt != null ? actualStartedAt : queuedAt;
            Instant endRef = endedAt != null ? endedAt : Instant.now();
            double elapsedSec = Math.max(0.001, Duration.between(startRef, endRef).toMillis() / 1000.0);
            m.put("sent", sentNow);
            m.put("failed", failedNow);
            m.put("elapsedSec", elapsedSec);
            m.put("achievedRate", sentNow / elapsedSec);
            m.put("p50LatMs", p50LatMs);
            m.put("p95LatMs", p95LatMs);
            m.put("p99LatMs", p99LatMs);
            return m;
        }
    }

    private final String runId;
    private final Instant startedAt;
    private final int restSecondsBetween;
    private final List<CellExecution> cells;

    private volatile int activeCellIndex = -1;
    private volatile Status status = Status.RUNNING;
    private volatile Instant endedAt;
    private volatile String lastError;

    public LoadTestRun(String runId, Instant startedAt, int restSecondsBetween, List<CellExecution> cells) {
        this.runId = runId;
        this.startedAt = startedAt;
        this.restSecondsBetween = restSecondsBetween;
        this.cells = cells;
    }

    public String runId() { return runId; }
    public Instant startedAt() { return startedAt; }
    public int restSecondsBetween() { return restSecondsBetween; }
    public List<CellExecution> cells() { return cells; }
    public int activeCellIndex() { return activeCellIndex; }
    public void setActiveCellIndex(int i) { this.activeCellIndex = i; }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public Instant endedAt() { return endedAt; }
    public void setEndedAt(Instant t) { this.endedAt = t; }
    public String lastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }

    public boolean isSweep() {
        return cells.size() > 1;
    }

    public Map<String, Object> toStatusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("kind", isSweep() ? "SWEEP" : "SINGLE");
        m.put("status", status.name());
        m.put("startedAt", startedAt.toString());
        m.put("endedAt", endedAt != null ? endedAt.toString() : null);
        m.put("activeCellIndex", activeCellIndex);
        m.put("restSecondsBetween", restSecondsBetween);
        m.put("lastError", lastError);

        long totalSent = 0, totalFailed = 0;
        List<Map<String, Object>> cellMaps = new ArrayList<>(cells.size());
        for (CellExecution c : cells) {
            totalSent += c.sent.sum();
            totalFailed += c.failed.sum();
            cellMaps.add(c.toMap());
        }
        m.put("totalSent", totalSent);
        m.put("totalFailed", totalFailed);
        m.put("cells", cellMaps);
        return m;
    }
}
