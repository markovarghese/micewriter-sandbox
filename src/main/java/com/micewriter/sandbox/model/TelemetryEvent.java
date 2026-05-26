package com.micewriter.sandbox.model;

import com.micewriter.sdk.annotation.IcebergEntity;
import com.micewriter.sdk.annotation.IcebergId;

import java.time.Instant;

/**
 * Reference {@code @IcebergEntity}. Each field maps to one Iceberg column.
 *
 * Column types resolved by the SDK:
 *   id       → string
 *   ts       → timestamptz (microseconds since epoch)
 *   source   → string
 *   payload  → string
 *   severity → int
 */
@IcebergEntity(table = "telemetry_events", namespace = {"micewriter"})
public class TelemetryEvent {

    @IcebergId
    private String id;

    private Instant ts;
    private String source;
    private String payload;
    private int severity;

    public TelemetryEvent() {}

    public TelemetryEvent(String id, Instant ts, String source, String payload, int severity) {
        this.id = id;
        this.ts = ts;
        this.source = source;
        this.payload = payload;
        this.severity = severity;
    }

    public String getId() { return id; }
    public Instant getTs() { return ts; }
    public String getSource() { return source; }
    public String getPayload() { return payload; }
    public int getSeverity() { return severity; }
}
