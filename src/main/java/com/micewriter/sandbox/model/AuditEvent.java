package com.micewriter.sandbox.model;

import com.micewriter.sdk.annotation.IcebergEntity;
import com.micewriter.sdk.annotation.IcebergId;

import java.time.Instant;

/**
 * Second {@code @IcebergEntity} bound to a different Iceberg table than
 * {@link TelemetryEvent}. Its purpose in the sandbox is to exercise the v2
 * SDK's per-table routing: writes to this class land on the
 * {@code audit_events} pipeline; writes to {@link TelemetryEvent} land on the
 * {@code telemetry_events} pipeline.
 *
 * <pre>
 * id       → string
 * ts       → timestamptz
 * actor    → string
 * action   → string
 * resource → string
 * </pre>
 */
@IcebergEntity(table = "audit_events", namespace = {"micewriter"})
public class AuditEvent {

    @IcebergId
    private String id;

    private Instant ts;
    private String actor;
    private String action;
    private String resource;

    public AuditEvent() {}

    public AuditEvent(String id, Instant ts, String actor, String action, String resource) {
        this.id = id;
        this.ts = ts;
        this.actor = actor;
        this.action = action;
        this.resource = resource;
    }

    public String getId() { return id; }
    public Instant getTs() { return ts; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResource() { return resource; }
}
