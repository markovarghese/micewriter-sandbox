# micewriter-sandbox
> Part of the [mIceWriter Ingestion Ecosystem](../micewriter-hub/README.md)

Reference Spring Boot microservice. Demonstrates end-to-end **v2** telemetry ingestion: HTTP request → SDK → gRPC → per-table engine pipeline → RocksDB → (every ~10 min) → MinIO Parquet → Nessie Iceberg commit. Writes to two Iceberg tables (`telemetry_events`, `audit_events`) so per-table SDK routing is exercised end-to-end.

> 📜 The v1 sandbox (sidecar-injected, UDS-based) is preserved on the `v1` branch and at the `v1.0.0` tag.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| `micewriter-local-infra` running | `powershell -ExecutionPolicy Bypass -File .\run.ps1 up` in that repo |
| `micewriter-engine` image pushed | `powershell -ExecutionPolicy Bypass -File .\push.ps1` in that repo |
| Docker Desktop | Builds the sandbox image |

There is no longer a `micewriter-k8s-injector` deployment step. v2 retires the sidecar admission webhook entirely; engine pipelines are deployed as Helm releases via `run.ps1 deploy` (see below).

## Deploying to k8s

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1 deploy
```

The `deploy` target now:
1. **Installs one engine pipeline per Iceberg table** (`engine-telemetry-events` and `engine-audit-events`) into the `micewriter-infra` namespace using [`micewriter-local-infra/charts/table-pipeline`](../micewriter-local-infra/charts/table-pipeline/README.md).
2. Builds the sandbox image (parent dir is the build context so `micewriter-sdk-java` source is co-built) and pushes to `k8s-node-1.local:5000`.
3. Applies the sandbox K8s manifests.

```powershell
# Tear down only the sandbox app (pipelines remain — useful for redeploys)
powershell -ExecutionPolicy Bypass -File .\run.ps1 undeploy

# Tear down only the pipelines
powershell -ExecutionPolicy Bypass -File .\run.ps1 pipelines-down

# Install pipelines without redeploying the app
powershell -ExecutionPolicy Bypass -File .\run.ps1 pipelines-up
```

## API Endpoints

### `POST /events` — ingest one `TelemetryEvent` to the `telemetry_events` pipeline
```bash
curl -X POST http://k8s-node-1.local/events \
  -H 'Content-Type: application/json' \
  -d '{"source": "my-service", "payload": "hello world", "severity": 2}'
# → {"id":"<uuid>","table":"telemetry_events","status":"ingested"}
```

### `POST /audit` — ingest one `AuditEvent` to the `audit_events` pipeline
Demonstrates v2's per-table SDK routing. The SDK reads `@IcebergEntity.table` off the `AuditEvent` class and dispatches over a different gRPC channel than `TelemetryEvent`.
```bash
curl -X POST http://k8s-node-1.local/audit \
  -H 'Content-Type: application/json' \
  -d '{"actor": "alice", "action": "login", "resource": "/dashboard"}'
# → {"id":"<uuid>","table":"audit_events","status":"ingested"}
```

### `POST /events/load?count=N` — load test (telemetry_events only)
```bash
curl -X POST "http://k8s-node-1.local/events/load?count=5000"
# → {"table":"telemetry_events","sent":5000,"elapsedMs":342,"throughputPerSec":14619}
```

### `POST /events/flush?table=<table>` — force a pipeline to commit
v2 routes the flush to the pipeline owning `table`. Defaults to `telemetry_events`. Requires `ENABLE_MANUAL_FLUSH=true` on that engine pipeline (the chart sets it `true` by default for local eval).
```bash
curl -X POST "http://k8s-node-1.local/events/flush"
curl -X POST "http://k8s-node-1.local/events/flush?table=audit_events"
# → {"table":"audit_events","status":"flushed"}
```

### `/loadtest/*` — in-process load generator
Same shape as v1. The hub spec at [`micewriter-hub/docs/load-testing-spec.md`](../micewriter-hub/docs/load-testing-spec.md) is the authoritative reference.

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/loadtest/start` | `{ rate, payloadSizeBytes, durationSec }` | `{ runId, status }` |
| `POST` | `/loadtest/sweep` | `{ restSecondsBetween, cells: [...] }` | `{ runId, status, cellCount }` |
| `GET`  | `/loadtest/{runId}` | — | per-cell counters + p50/p95/p99 |
| `GET`  | `/loadtest` | — | list of recent runs |
| `POST` | `/loadtest/{runId}/stop` | — | `{ status: "STOPPED" }` |

> **View the Results**: Baseline results live in [`load-tests/results/results.md`](load-tests/results/results.md).

### `GET /actuator/health`
```bash
curl http://k8s-node-1.local/actuator/health
# → {"status":"UP"}
```

## Verifying the Full Pipeline

After running a load test, wait for the engine flush cycle (~10 min, or trigger manually):

```bash
# 1. Engine pod logs — pipelines live in the micewriter-infra namespace
kubectl logs -n micewriter-infra deploy/engine-telemetry-events --follow

# 2. MinIO: Parquet files appear in the iceberg bucket
#    Open http://k8s-node-1.local:9001 → browse iceberg/micewriter/telemetry_events/

# 3. Nessie: table and snapshot visible
curl http://k8s-node-1.local:19120/api/v1/trees/main/entries
```

## Automated E2E Integration Tests

`SystemE2EIT.java` sends 1000 events to `/events/load`, forces a flush via `/events/flush`, and asserts 1000 new rows in the Iceberg table. The test is named `*IT.java` so it runs in Maven's `verify` phase (via `maven-failsafe-plugin`).

### Prerequisites

1. Local data lake up: `cd ../micewriter-local-infra && powershell -ExecutionPolicy Bypass -File .\run.ps1 up`
2. Engine image pushed: `cd ../micewriter-engine && powershell -ExecutionPolicy Bypass -File .\push.ps1`
3. Sandbox + pipelines deployed: `powershell -ExecutionPolicy Bypass -File .\run.ps1 deploy`

### Running the test

```bash
# Run from the parent directory that contains both repos (..)
docker run --rm --network host \
  -v "$(pwd):/repos" \
  -v "$HOME/.m2:/root/.m2" \
  -w /repos/micewriter-sandbox \
  maven:3.9-eclipse-temurin-17 \
  mvn "-Dit.test=SystemE2EIT" \
      "-Dapp.url=http://k8s-node-1.local" \
      "-Dnessie.uri=http://k8s-node-1.local:19120/api/v1" \
      "-Dminio.url=http://k8s-node-1.local:9000" \
      verify
```

### Debugging a failure

If the test times out waiting for new rows:

```bash
kubectl logs -n micewriter-infra deploy/engine-telemetry-events --tail=100 -f
```

Look for `engine in backpressure`, `Failed to compile CF`, or `Table flush failed` — those carry the underlying error.

## File Structure

```
micewriter-sandbox/
  pom.xml
  Dockerfile              # build context = parent dir (builds SDK first)
  run.ps1                 # Windows entry point (deploy / undeploy / pipelines-up / pipelines-down)
  skaffold.yaml           # alternative for Linux/Mac users
  src/main/java/com/micewriter/sandbox/
    SandboxApplication.java
    controller/
      TelemetryController.java   # POST /events, /events/load, /audit, /events/flush
      LoadTestController.java    # /loadtest/*
    model/
      TelemetryEvent.java        # @IcebergEntity(table="telemetry_events")
      AuditEvent.java            # @IcebergEntity(table="audit_events")
    loadtest/
      LoadTestService.java, LoadTestRun.java
  src/main/resources/
    application.yml              # v2 micewriter.resolver + retry budgets
  k8s/
    namespace.yaml
    deployment.yaml              # no sidecar injection in v2; MICEWRITER_RESOLVER env points cross-ns
    service.yaml                 # LoadBalancer on port 80→8080
    ingress.yaml
```
