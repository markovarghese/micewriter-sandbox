# micewriter-sandbox
> Part of the [mIceWriter Ingestion Ecosystem](../micewriter-hub/README.md)

Reference Spring Boot microservice. Demonstrates end-to-end telemetry ingestion through the mIceWriter pipeline: HTTP request → SDK → UDS → engine → RocksDB → (every ~10 min) → MinIO Parquet → Nessie Iceberg commit.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| `micewriter-local-infra` running | `.\run.ps1 up` in that repo |
| `micewriter-engine` image pushed | `.\push.ps1` in that repo |
| `micewriter-k8s-injector` deployed | `.\run.ps1 push` then `.\run.ps1 deploy` in that repo |
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
.\run.ps1 deploy
```

This builds the image using the parent directory as the Docker build context (so both
`micewriter-sdk-java` and `micewriter-sandbox` source trees are available), pushes it to
`k8s-node-1.local:5000`, and applies the k8s manifests.

```powershell
# Tear down
.\run.ps1 undeploy
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

An automated E2E test suite (`SystemE2EIT.java`) validates the full data ingestion pipeline without waiting for the 10-minute flush cycle. It sends traffic, calls the `/events/flush` API, and verifies the materialized Iceberg rows in the Nessie catalog.

Since the test connects to the `k8s-node-1.local` testbed cluster and relies on the `micewriter-sdk-java` SDK, you can run it consistently via a Dockerized Maven environment from the root repository workspace:

```bash
docker run --rm \
  --add-host k8s-node-1.local:192.168.69.1 \
  -v ~/.m2:/root/.m2 \
  -v $(pwd):/workspace \
  -w /workspace/micewriter-sandbox \
  maven:3.9-eclipse-temurin-17 mvn test -Dtest=SystemE2EIT
```

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
