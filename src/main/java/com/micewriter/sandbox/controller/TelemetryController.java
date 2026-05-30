package com.micewriter.sandbox.controller;

import com.micewriter.sandbox.model.TelemetryEvent;
import com.micewriter.sdk.template.IcebergStreamTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/events")
public class TelemetryController {

    private final IcebergStreamTemplate icebergTemplate;

    public TelemetryController(IcebergStreamTemplate icebergTemplate) {
        this.icebergTemplate = icebergTemplate;
    }

    /**
     * Ingest a single telemetry event.
     *
     * <pre>
     * POST /events
     * { "source": "my-service", "payload": "hello", "severity": 1 }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody EventRequest request) {
        TelemetryEvent event = new TelemetryEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                request.getSource() != null ? request.getSource() : "sandbox",
                request.getPayload(),
                request.getSeverity()
        );
        icebergTemplate.send(event);
        return ResponseEntity.ok(Map.of(
                "id", event.getId(),
                "status", "ingested"
        ));
    }

    /**
     * Generate {@code count} synthetic events and stream them all.
     * Useful for load testing and verifying the flush pipeline end-to-end.
     *
     * <pre>
     * POST /events/load?count=1000
     * </pre>
     */
    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> loadTest(@RequestParam(defaultValue = "100") int count) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            TelemetryEvent event = new TelemetryEvent(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    "load-test",
                    "synthetic event " + i,
                    i % 5 + 1
            );
            icebergTemplate.send(event);
        }
        long elapsed = System.currentTimeMillis() - start;
        return ResponseEntity.ok(Map.of(
                "sent", count,
                "elapsedMs", elapsed,
                "throughputPerSec", count * 1000L / Math.max(elapsed, 1)
        ));
    }

    /**
     * Trigger a manual flush of the engine's RocksDB buffer to Iceberg.
     * Note: Requires ENABLE_MANUAL_FLUSH=true on the engine sidecar.
     *
     * <pre>
     * POST /events/flush
     * </pre>
     */
    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flush() {
        icebergTemplate.flushNow();
        return ResponseEntity.ok(Map.of("status", "flushed"));
    }

    // -------------------------------------------------------------------------

    public static class EventRequest {
        private String source;
        private String payload;
        private int severity = 1;

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getPayload() { return payload; }
        public void setPayload(String payload) { this.payload = payload; }

        public int getSeverity() { return severity; }
        public void setSeverity(int severity) { this.severity = severity; }
    }
}
