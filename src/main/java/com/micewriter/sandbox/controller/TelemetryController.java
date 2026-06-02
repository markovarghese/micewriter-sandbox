package com.micewriter.sandbox.controller;

import com.micewriter.sandbox.model.AuditEvent;
import com.micewriter.sandbox.model.TelemetryEvent;
import com.micewriter.sdk.template.IcebergStreamTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
public class TelemetryController {

    private final IcebergStreamTemplate icebergTemplate;

    public TelemetryController(IcebergStreamTemplate icebergTemplate) {
        this.icebergTemplate = icebergTemplate;
    }

    /**
     * Ingest a single {@link TelemetryEvent} to the {@code telemetry_events} pipeline.
     *
     * <pre>
     * POST /events
     * { "source": "my-service", "payload": "hello", "severity": 1 }
     * </pre>
     */
    @PostMapping("/events")
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
                "table", "telemetry_events",
                "status", "ingested"
        ));
    }

    /**
     * Generate {@code count} synthetic {@link TelemetryEvent} records and stream them.
     */
    @PostMapping("/events/load")
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
                "table", "telemetry_events",
                "sent", count,
                "elapsedMs", elapsed,
                "throughputPerSec", count * 1000L / Math.max(elapsed, 1)
        ));
    }

    /**
     * Ingest a single {@link AuditEvent} to the {@code audit_events} pipeline.
     * Demonstrates v2 per-table routing — the SDK opens a separate gRPC
     * channel to a different pipeline endpoint based on the entity class.
     *
     * <pre>
     * POST /audit
     * { "actor": "alice", "action": "login", "resource": "/dashboard" }
     * </pre>
     */
    @PostMapping("/audit")
    public ResponseEntity<Map<String, Object>> auditIngest(@RequestBody AuditRequest request) {
        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                request.getActor() != null ? request.getActor() : "system",
                request.getAction() != null ? request.getAction() : "unknown",
                request.getResource() != null ? request.getResource() : ""
        );
        icebergTemplate.send(event);
        return ResponseEntity.ok(Map.of(
                "id", event.getId(),
                "table", "audit_events",
                "status", "ingested"
        ));
    }

    /**
     * Force the pipeline serving {@code table} to commit immediately. Requires
     * the engine to be configured with {@code ENABLE_MANUAL_FLUSH=true}.
     *
     * <pre>
     * POST /events/flush?table=telemetry_events
     * </pre>
     */
    @PostMapping("/events/flush")
    public ResponseEntity<Map<String, Object>> flush(
            @RequestParam(defaultValue = "telemetry_events") String table) {
        icebergTemplate.flushNow(table);
        return ResponseEntity.ok(Map.of("table", table, "status", "flushed"));
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

    public static class AuditRequest {
        private String actor;
        private String action;
        private String resource;

        public String getActor() { return actor; }
        public void setActor(String actor) { this.actor = actor; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public String getResource() { return resource; }
        public void setResource(String resource) { this.resource = resource; }
    }
}
