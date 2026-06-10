package com.micewriter.sandbox.loadtest;

import com.micewriter.sandbox.model.TelemetryEvent;
import com.micewriter.sdk.template.IcebergStreamTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LoadTestService {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);
    private static final int HISTORY_LIMIT = 32;

    private final IcebergStreamTemplate icebergTemplate;
    private final MeterRegistry meterRegistry;
    private final int senderThreads;

    private final ScheduledExecutorService tickExecutor;
    private final ScheduledExecutorService coordinatorExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "loadtest-coordinator");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<LoadTestRun> active = new AtomicReference<>();
    private volatile List<ScheduledFuture<?>> activeTicks = List.of();
    private volatile ScheduledFuture<?> activeStopper;

    private final Map<String, LoadTestRun> history = Collections.synchronizedMap(
            new LinkedHashMap<String, LoadTestRun>(HISTORY_LIMIT + 1, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LoadTestRun> eldest) {
                    return size() > HISTORY_LIMIT;
                }
            });

    public LoadTestService(IcebergStreamTemplate icebergTemplate, MeterRegistry meterRegistry) {
        this.icebergTemplate = icebergTemplate;
        this.meterRegistry = meterRegistry;
        String envVal = System.getenv("LOADTEST_SENDER_THREADS");
        this.senderThreads = envVal != null ? Math.max(1, Integer.parseInt(envVal.trim())) : 1;
        this.tickExecutor = Executors.newScheduledThreadPool(this.senderThreads, r -> {
            Thread t = new Thread(r, "loadtest-tick");
            t.setDaemon(true);
            return t;
        });
        log.info("Load test sender threads: {}", senderThreads);
    }

    public LoadTestRun startSingle(int rate, int payloadSizeBytes, int durationSec) {
        validateCell(rate, payloadSizeBytes, durationSec);
        String runId = UUID.randomUUID().toString();
        LoadTestRun.CellExecution cell = buildCell(rate, payloadSizeBytes, durationSec, Instant.now());
        LoadTestRun run = new LoadTestRun(runId, Instant.now(), 0, List.of(cell));
        claim(run);
        history.put(runId, run);
        runCellAsync(run, 0);
        return run;
    }

    public LoadTestRun startSweep(List<int[]> cellsParams, int restSecondsBetween) {
        if (cellsParams == null || cellsParams.isEmpty()) {
            throw new IllegalArgumentException("sweep requires at least one cell");
        }
        for (int[] p : cellsParams) {
            validateCell(p[0], p[1], p[2]);
        }
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<LoadTestRun.CellExecution> cells = new ArrayList<>(cellsParams.size());
        for (int[] p : cellsParams) {
            cells.add(buildCell(p[0], p[1], p[2], now));
        }
        LoadTestRun run = new LoadTestRun(runId, now, Math.max(0, restSecondsBetween), cells);
        claim(run);
        history.put(runId, run);
        coordinatorExecutor.submit(() -> driveSweep(run));
        return run;
    }

    public LoadTestRun get(String runId) {
        return history.get(runId);
    }

    public Collection<LoadTestRun> list() {
        synchronized (history) {
            return new ArrayList<>(history.values());
        }
    }

    public LoadTestRun activeRun() {
        return active.get();
    }

    public boolean stop(String runId) {
        LoadTestRun run = active.get();
        if (run == null || !run.runId().equals(runId)) {
            return false;
        }
        cancelTimers();
        finalizeActiveCell(run);
        run.setStatus(LoadTestRun.Status.STOPPED);
        run.setEndedAt(Instant.now());
        active.compareAndSet(run, null);
        log.info("Load test {} stopped by request", runId);
        return true;
    }

    @PreDestroy
    public void shutdown() {
        cancelTimers();
        tickExecutor.shutdownNow();
        coordinatorExecutor.shutdownNow();
    }

    // -------------------------------------------------------------------------

    private void validateCell(int rate, int payloadSizeBytes, int durationSec) {
        if (rate <= 0) throw new IllegalArgumentException("rate must be > 0");
        if (senderThreads == 1 && rate > 10_000) throw new IllegalArgumentException("rate above 10000 ev/s exceeds the SDK serialization lock's practical limit");
        if (payloadSizeBytes < 0) throw new IllegalArgumentException("payloadSizeBytes must be >= 0");
        if (payloadSizeBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("payloadSizeBytes above 64 MB risks engine's 128 MB UDS frame limit after CBOR encode");
        if (durationSec <= 0) throw new IllegalArgumentException("durationSec must be > 0");
        if (durationSec > 24 * 3600) throw new IllegalArgumentException("durationSec above 24h is almost certainly a typo");
    }

    private LoadTestRun.CellExecution buildCell(int rate, int payloadSizeBytes, int durationSec, Instant startedAt) {
        int poolSize = 50;
        List<TelemetryEvent> templates = new ArrayList<>(poolSize);
        int targetElements = Math.max(1, payloadSizeBytes / 8);

        for (int i = 0; i < poolSize; i++) {
            TelemetryEvent template = new TelemetryEvent();
            template.setMl_service_name("vrbo-rank-nova-model");
            template.setMl_service_version("1.0");

            List<Double> t2vec = new ArrayList<>(targetElements);
            for (int j = 0; j < targetElements; j++) {
                t2vec.add(java.util.concurrent.ThreadLocalRandom.current().nextDouble());
            }
            template.setDouble_field_1(t2vec);

            String randStr = "random_string_" + java.util.UUID.randomUUID().toString();
            List<String> defString = List.of(randStr);
            List<Double> defDouble = List.of(java.util.concurrent.ThreadLocalRandom.current().nextDouble());
            List<Integer> defInteger = List.of(java.util.concurrent.ThreadLocalRandom.current().nextInt());

            template.setString_field_1(defString);
            template.setDouble_field_2(defDouble);
            template.setString_field_2(defString);
            template.setString_field_3(defString);
            template.setString_field_4(defString);
            template.setString_field_5(defString);
            template.setDouble_field_3(defDouble);
            template.setDouble_field_4(defDouble);
            template.setInt_field_1(defInteger);
            template.setDouble_field_5(defDouble);
            template.setString_field_6(defString);
            template.setInt_field_2(defInteger);
            template.setInt_field_3(defInteger);
            template.setDouble_field_6(defDouble);
            template.setString_field_7(defString);
            template.setString_field_8(defString);
            template.setDouble_field_7(defDouble);
            template.setString_field_9(defString);
            template.setDouble_field_8(defDouble);
            template.setString_field_10(defString);
            template.setInt_field_4(defInteger);

            templates.add(template);
        }

        return new LoadTestRun.CellExecution(rate, payloadSizeBytes, durationSec, "Payload: " + payloadSizeBytes + " bytes", templates, startedAt);
    }

    private void claim(LoadTestRun run) {
        if (!active.compareAndSet(null, run)) {
            throw new IllegalStateException("another load test is already running: " + active.get().runId());
        }
    }

    private void runCellAsync(LoadTestRun run, int cellIndex) {
        LoadTestRun.CellExecution cell = run.cells().get(cellIndex);
        cell.timer = Timer.builder("micewriter.loadtest.send")
                .description("Latency of icebergTemplate.sendAsync() during a load test")
                .tags(Tags.of(
                        "run_id", run.runId(),
                        "cell_index", String.valueOf(cellIndex),
                        "rate", String.valueOf(cell.rate),
                        "payload_size_bytes", String.valueOf(cell.payloadSizeBytes)
                ))
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        run.setActiveCellIndex(cellIndex);
        cell.actualStartedAt = Instant.now();

        String runIdAtStart = run.runId();

        // Shared send-one-event logic used by both the paced (single-thread) and
        // unpaced (multi-thread) paths.
        Runnable sendOnce = () -> {
            if (active.get() != run || run.status() != LoadTestRun.Status.RUNNING) {
                return;
            }
            int templateIndex = java.util.concurrent.ThreadLocalRandom.current().nextInt(cell.templateEvents.size());
            TelemetryEvent baseTemplate = cell.templateEvents.get(templateIndex);

            TelemetryEvent event = new TelemetryEvent();
            event.setEvent_uuid(UUID.randomUUID().toString());
            event.setPublished_timestamp(Instant.now());
            event.setMl_service_name(baseTemplate.getMl_service_name());
            event.setMl_service_version(baseTemplate.getMl_service_version());

            event.setDouble_field_1(baseTemplate.getDouble_field_1());
            event.setString_field_1(baseTemplate.getString_field_1());
            event.setDouble_field_2(baseTemplate.getDouble_field_2());
            event.setString_field_2(baseTemplate.getString_field_2());
            event.setString_field_3(baseTemplate.getString_field_3());
            event.setString_field_4(baseTemplate.getString_field_4());
            event.setString_field_5(baseTemplate.getString_field_5());
            event.setDouble_field_3(baseTemplate.getDouble_field_3());
            event.setDouble_field_4(baseTemplate.getDouble_field_4());
            event.setInt_field_1(baseTemplate.getInt_field_1());
            event.setDouble_field_5(baseTemplate.getDouble_field_5());
            event.setString_field_6(baseTemplate.getString_field_6());
            event.setInt_field_2(baseTemplate.getInt_field_2());
            event.setInt_field_3(baseTemplate.getInt_field_3());
            event.setDouble_field_6(baseTemplate.getDouble_field_6());
            event.setString_field_7(baseTemplate.getString_field_7());
            event.setString_field_8(baseTemplate.getString_field_8());
            event.setDouble_field_7(baseTemplate.getDouble_field_7());
            event.setString_field_9(baseTemplate.getString_field_9());
            event.setDouble_field_8(baseTemplate.getDouble_field_8());
            event.setString_field_10(baseTemplate.getString_field_10());
            event.setInt_field_4(baseTemplate.getInt_field_4());

            // Pipelined send: fire and let the SDK's in-flight byte budget apply backpressure.
            // Counters and latency are recorded on ACK completion — all thread-safe.
            final long sendStartNanos = System.nanoTime();
            try {
                icebergTemplate.sendAsync(event).whenComplete((v, err) -> {
                    cell.timer.record(System.nanoTime() - sendStartNanos, TimeUnit.NANOSECONDS);
                    if (err == null) {
                        cell.sent.increment();
                        meterRegistry.counter("micewriter.loadtest.events.sent",
                                Tags.of("run_id", runIdAtStart, "cell_index", String.valueOf(cellIndex))).increment();
                    } else {
                        Throwable cause = (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
                                ? err.getCause() : err;
                        cell.failed.increment();
                        run.setLastError(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                        meterRegistry.counter("micewriter.loadtest.events.failed",
                                Tags.of("run_id", runIdAtStart, "cell_index", String.valueOf(cellIndex))).increment();
                    }
                });
            } catch (Throwable t) {
                // Synchronous failure before the future was returned (serialization, interrupted
                // budget acquisition, >16 MB payload). Swallow — one bad send shouldn't kill the run.
                cell.timer.record(System.nanoTime() - sendStartNanos, TimeUnit.NANOSECONDS);
                cell.failed.increment();
                run.setLastError(t.getClass().getSimpleName() + ": " + t.getMessage());
                meterRegistry.counter("micewriter.loadtest.events.failed",
                        Tags.of("run_id", runIdAtStart, "cell_index", String.valueOf(cellIndex))).increment();
            }
        };

        if (senderThreads > 1) {
            // Paced multi-thread mode: use tickExecutor thread pool to fire paced sends
            // distributed evenly across the senderThreads. If a thread blocks due to
            // backpressure, the subsequent ticks back up automatically (scheduleAtFixedRate behavior).
            long periodNanos = Math.max(1, (1_000_000_000L * senderThreads) / cell.rate);
            long staggerNanos = Math.max(1, 1_000_000_000L / cell.rate);
            List<ScheduledFuture<?>> futures = new ArrayList<>(senderThreads);
            for (int i = 0; i < senderThreads; i++) {
                futures.add(tickExecutor.scheduleWithFixedDelay(sendOnce, i * staggerNanos, periodNanos, TimeUnit.NANOSECONDS));
            }
            activeTicks = futures;
            log.info("Started {} paced sender threads for cell {} of run {}", senderThreads, cellIndex, run.runId());
        } else {
            // Single-thread paced mode: one tick per 1/rate seconds (original behavior).
            long periodNanos = Math.max(1, 1_000_000_000L / cell.rate);
            activeTicks = List.of(tickExecutor.scheduleWithFixedDelay(sendOnce, 0, periodNanos, TimeUnit.NANOSECONDS));
        }

        // Schedule the cell stop. For single-cell runs, the stop transitions the whole run to DONE.
        // For sweeps, the coordinator (driveSweep) handles cell transitions instead, so we don't auto-schedule.
        if (!run.isSweep()) {
            activeStopper = coordinatorExecutor.schedule(() -> finishSingle(run), cell.durationSec, TimeUnit.SECONDS);
        }
    }

    private void driveSweep(LoadTestRun run) {
        try {
            for (int i = 0; i < run.cells().size(); i++) {
                if (active.get() != run || run.status() != LoadTestRun.Status.RUNNING) return;
                LoadTestRun.CellExecution cell = run.cells().get(i);
                runCellAsync(run, i);
                long deadlineMs = System.currentTimeMillis() + cell.durationSec * 1000L;
                while (System.currentTimeMillis() < deadlineMs) {
                    if (active.get() != run || run.status() != LoadTestRun.Status.RUNNING) return;
                    Thread.sleep(200);
                }
                cancelTimers();
                finalizeCell(cell);
                if (i < run.cells().size() - 1 && run.restSecondsBetween() > 0) {
                    long restEndMs = System.currentTimeMillis() + run.restSecondsBetween() * 1000L;
                    while (System.currentTimeMillis() < restEndMs) {
                        if (active.get() != run || run.status() != LoadTestRun.Status.RUNNING) return;
                        Thread.sleep(200);
                    }
                }
            }
            run.setStatus(LoadTestRun.Status.DONE);
            run.setEndedAt(Instant.now());
            active.compareAndSet(run, null);
            log.info("Load sweep {} completed: {} cells", run.runId(), run.cells().size());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            run.setStatus(LoadTestRun.Status.STOPPED);
            run.setEndedAt(Instant.now());
            active.compareAndSet(run, null);
        } catch (Throwable t) {
            log.error("Sweep coordinator failed for run {}", run.runId(), t);
            run.setLastError(t.getClass().getSimpleName() + ": " + t.getMessage());
            run.setStatus(LoadTestRun.Status.FAILED);
            run.setEndedAt(Instant.now());
            active.compareAndSet(run, null);
        }
    }

    private void finishSingle(LoadTestRun run) {
        if (active.get() != run) return;
        cancelTimers();
        finalizeActiveCell(run);
        run.setStatus(LoadTestRun.Status.DONE);
        run.setEndedAt(Instant.now());
        active.compareAndSet(run, null);
        log.info("Load test {} completed", run.runId());
    }

    private void finalizeActiveCell(LoadTestRun run) {
        int idx = run.activeCellIndex();
        if (idx < 0 || idx >= run.cells().size()) return;
        finalizeCell(run.cells().get(idx));
    }

    private void finalizeCell(LoadTestRun.CellExecution cell) {
        cell.endedAt = Instant.now();
        if (cell.timer != null) {
            HistogramSnapshot snap = cell.timer.takeSnapshot();
            for (ValueAtPercentile v : snap.percentileValues()) {
                double ms = v.value(TimeUnit.MILLISECONDS);
                if (Math.abs(v.percentile() - 0.5) < 0.001) cell.p50LatMs = ms;
                else if (Math.abs(v.percentile() - 0.95) < 0.001) cell.p95LatMs = ms;
                else if (Math.abs(v.percentile() - 0.99) < 0.001) cell.p99LatMs = ms;
            }
        }
    }

    private void cancelTimers() {
        if (activeTicks != null) {
            for (ScheduledFuture<?> f : activeTicks) {
                if (f != null) f.cancel(true); // Must interrupt to unblock semaphore or wait
            }
            activeTicks = List.of();
        }
        if (activeStopper != null) {
            activeStopper.cancel(false);
            activeStopper = null;
        }
    }

}
