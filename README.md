# micewriter-sandbox
> Part of the [mIceWriter Ingestion Ecosystem](../micewriter-hub/README.md)

Reference Spring Boot microservice. Demonstrates end-to-end telemetry ingestion through the mIceWriter pipeline: HTTP request → SDK → UDS → engine → RocksDB → (every ~10 min) → MinIO Parquet → Nessie Iceberg commit.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| `micewriter-local-infra` running | `powershell -ExecutionPolicy Bypass -File .\run.ps1 up` in that repo |
| `micewriter-engine` image pushed | `powershell -ExecutionPolicy Bypass -File .\push.ps1` in that repo |
| `micewriter-k8s-injector` deployed | `powershell -ExecutionPolicy Bypass -File .\run.ps1 push` then `powershell -ExecutionPolicy Bypass -File .\run.ps1 deploy` in that repo |
| Docker Desktop | Builds the image |

## Running Locally (without k8s)

For local dev, the engine must be running separately and the socket path must be reachable:

```bash
# Terminal 1 — start the engine (requires Linux/WSL or Docker)
MINIO_URL=http://k8s-node-1.local:9000 \
MINIO_ACCESS_KEY=micewriter \
MINIO_SECRET_KEY=micewriter123 \
NESSIE_URI=http://k8s-node-1.local:19120/iceberg/v1 \
SOCKET_PATH=/tmp/iceberg.sock \
./micewriter-engine

# Terminal 2 — start the sandbox
MICEWRITER_SOCKET_PATH=/tmp/iceberg.sock mvn spring-boot:run
```

## Deploying to k8s

```powershell
# From the micewriter-sandbox directory
powershell -ExecutionPolicy Bypass -File .\run.ps1 deploy
```

This builds the image using the parent directory as the Docker build context (so both
`micewriter-sdk-java` and `micewriter-sandbox` source trees are available), pushes it to
`k8s-node-1.local:5000`, and applies the k8s manifests.

```powershell
# Tear down
powershell -ExecutionPolicy Bypass -File .\run.ps1 undeploy
```

The deployment uses the `iceberg-stream.yourcompany.com/inject: "true"` annotation. The
webhook automatically injects the engine sidecar, the shared UDS socket volume, and the
RocksDB PVC.

## API Endpoints

### POST `/events` — ingest one event
```bash
curl -X POST http://k8s-node-1.local/events \
  -H 'Content-Type: application/json' \
  -d '{"source": "my-service", "payload": "hello world", "severity": 2}'
# → {"id":"<uuid>","status":"ingested"}
```

### POST `/events/load?count=N` — load test
```bash
curl -X POST "http://k8s-node-1.local/events/load?count=5000"
# → {"sent":5000,"elapsedMs":342,"throughputPerSec":14619}
```

### POST `/events/flush` — manually trigger commit
```bash
curl -X POST "http://k8s-node-1.local/events/flush"
# → {"status":"flushed"}
```

### `/loadtest/*` — in-process load generator

Server-driven load test. The sandbox runs the generator itself (no `k6`
installation required) and exposes counters + p50/p95/p99 latency per cell.
The hub spec at
[`micewriter-hub/docs/load-testing-spec.md`](../micewriter-hub/docs/load-testing-spec.md)
is the authoritative reference for request/response semantics and the
recommended sweep matrix.

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/loadtest/start` | `{ rate, payloadSizeBytes, durationSec }` | `{ runId, status }` |
| `POST` | `/loadtest/sweep` | `{ restSecondsBetween, cells: [{rate, payloadSizeBytes, durationSec}, …] }` | `{ runId, status, cellCount }` |
| `GET`  | `/loadtest/{runId}` | — | per-cell counters + p50/p95/p99 |
| `GET`  | `/loadtest` | — | list of recent runs (newest first) |
| `POST` | `/loadtest/{runId}/stop` | — | `{ status: "STOPPED" }` |

Only one run (single or sweep) can be active at a time; concurrent
`POST /loadtest/start` or `POST /loadtest/sweep` returns `409 CONFLICT`
with the active `runId`.

```bash
# Single scenario: 100 events/sec × 1 KB × 60 s
curl -X POST http://k8s-node-1.local/loadtest/start \
  -H 'Content-Type: application/json' \
  -d '{"rate":100,"payloadSizeBytes":1024,"durationSec":60}'
# → {"runId":"<uuid>","status":"RUNNING"}

# Poll for results
curl http://k8s-node-1.local/loadtest/<runId>
```

### GET `/actuator/health`
```bash
curl http://k8s-node-1.local/actuator/health
# → {"status":"UP"}
```

## Verifying the Full Pipeline

After running a load test, wait ~10 minutes for the engine flush cycle:

```bash
# 1. Check engine sidecar logs
kubectl logs -n micewriter-sandbox deploy/micewriter-sandbox -c micewriter-engine --follow

# 2. MinIO: Parquet files appear in the iceberg bucket
#    Open http://k8s-node-1.local:9001 → browse iceberg/micewriter/telemetry_events/

# 3. Nessie: table and snapshot visible
curl http://k8s-node-1.local:19120/api/v1/trees/main/entries
```

## Automated E2E Integration Tests

`SystemE2EIT.java` validates the full ingestion pipeline without waiting for the
10-minute flush cycle: it sends 1000 events, calls `/events/flush`, and asserts
that exactly 1000 new rows are visible via the Nessie/Iceberg catalog.

The test is named `*IT.java`, so it runs in the Maven `verify` phase (via
`maven-failsafe-plugin`) — not `test`. It is selected with `-Dit.test=...`,
not `-Dtest=...`.

### Prerequisites

1. The local k3s cluster, MinIO, and Nessie are running (see `micewriter-local-infra`).
2. The latest `micewriter-engine` image is pushed to the k3s registry
   (`powershell -ExecutionPolicy Bypass -File .\push.ps1` in that repo).
3. The sandbox is deployed (`powershell -ExecutionPolicy Bypass -File .\run.ps1 deploy`).
4. If you've changed the engine sidecar, restart the sandbox to pull the
   newest image:
   ```powershell
   kubectl rollout restart deployment/micewriter-sandbox -n micewriter-sandbox
   kubectl rollout status  deployment/micewriter-sandbox -n micewriter-sandbox --timeout=180s
   ```

### Running the test

The test connects to `k8s-node-1.local` over the host network and relies on the
`micewriter-sdk-java` SDK being available in the local Maven repository. Run it
in a Dockerized Maven so versions stay consistent across machines.

**1. Install the SDK into the host `~/.m2` (only required after SDK changes):**

```bash
# Run from the parent directory that contains both repos (..)
docker run --rm \
  -v "$(pwd):/repos" \
  -v "$HOME/.m2:/root/.m2" \
  -w /repos/micewriter-sdk-java \
  maven:3.9-eclipse-temurin-17 \
  mvn -q install -DskipTests
```

**2. Run the integration test:**

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

Mounting `$HOME/.m2:/root/.m2` caches Maven dependencies on the host so reruns
don't redownload ~300 MB each time. `--network host` is required so the test
JVM can resolve `k8s-node-1.local` exactly as the host does.

### Windows / Git Bash notes

On Windows with Git Bash or MSYS, the shell rewrites `/repos` into a Windows
path before Docker sees it, breaking the working directory. Prefix the command
with `MSYS_NO_PATHCONV=1` and use full Windows-style paths for the bind mounts:

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network host \
  -v "C:/Users/<you>/source/repos:/repos" \
  -v "C:/Users/<you>/.m2:/root/.m2" \
  -w /repos/micewriter-sandbox \
  maven:3.9-eclipse-temurin-17 \
  mvn "-Dit.test=SystemE2EIT" \
      "-Dapp.url=http://k8s-node-1.local" \
      "-Dnessie.uri=http://k8s-node-1.local:19120/api/v1" \
      "-Dminio.url=http://k8s-node-1.local:9000" \
      verify
```

### Debugging a failure

If the test times out waiting for new rows, the engine likely failed to
compile or commit. Tail the sidecar logs while the test runs:

```bash
kubectl logs -n micewriter-sandbox deployment/micewriter-sandbox \
  -c micewriter-engine --tail=100 -f
```

Look for `Failed to compile CF` or `Table flush failed` lines — those carry
the underlying Arrow / Parquet / Iceberg error.

## File Structure

```
micewriter-sandbox/
  pom.xml
  Dockerfile              # build context = parent dir (builds SDK first)
  run.ps1                 # Windows entry point (deploy / undeploy)
  skaffold.yaml           # alternative for Linux/Mac users
  src/main/java/com/micewriter/sandbox/
    SandboxApplication.java
    controller/
      TelemetryController.java   # POST /events, POST /events/load
    model/
      TelemetryEvent.java        # @IcebergEntity — the Iceberg table schema
  src/main/resources/
    application.yml
  k8s/
    namespace.yaml
    deployment.yaml              # inject annotation here
    service.yaml                 # LoadBalancer on port 80→8080
```
