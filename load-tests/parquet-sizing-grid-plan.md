# Parquet-sizing load-test grid (v1 engine)

**Status:** ready to run **once the byte-threshold + pre-upload-drop-sink engine build lands**
(the build that adds `TARGET_PARQUET_BYTES` and moves the drop-sink to just before MinIO/Nessie).
**Owner decision this resolves:** can we hit Trino-friendly **~128 MiB** Parquet files on the
512 MiB sidecar without OOMKill, and if so by which lever — or do we accept ~96 MiB, or build the
cross-CF persistent writer.

---

## 1. The decision this grid makes

128 MiB is a *nice-to-have* (Trino likes 128 MiB–1 GiB data files; ~96 MiB is already healthy).
The grid picks exactly one of three outcomes:

| Outcome | Trigger in the data | Action | Cost |
|---|---|---|---|
| **A. Ship ~96 MiB** | G2/G3 pass; 128 MiB unreachable without OOM or unacceptable throughput | Set `FLUSH_SIZE_BYTES`/`TARGET_PARQUET_BYTES` ≈ 96 MiB, done | none |
| **B. 128 MiB via concurrency cap (± small RAM bump)** | G4 (conc=2) OOMs but G5 (conc=1, real 512) or G6 (conc=1, 640 MiB) passes | Make `ENGINE_MEM_LIMIT_BYTES` / a `concurrent_cf_flushes` cap production-configurable; ship 128 MiB | small code change |
| **C. Persistent cross-CF writer** | 128 MiB needs *small* CFs + *low* RAM simultaneously (G5/G6 fail or backpressure unacceptable) but G8 confirms writer mem can be bounded to the target | Write the persistent-writer spec, then implement | large change + durability rework |

**Why the per-CF ceiling exists:** the compressor is per-CF, so a Parquet file can never exceed one
CF's worth of a table (`file ≤ FLUSH_SIZE_BYTES`), and the dominant RAM term is
`writer_buffer (~file size) × concurrent_cf_flushes`. At 512 MiB the engine auto-sets
`concurrent_cf_flushes = floor(mem / 256 MiB) = 2`, so two ~128 MiB writers can coexist → the
expected squeeze. The grid's job is to measure this precisely (allocator retention + RocksDB cache
make the baseline hard to predict) and find the cheapest lever that fits 128 MiB.

---

## 2. Prerequisites

### 2.1 Engine build
- Build with **`TARGET_PARQUET_BYTES`** (byte-threshold Parquet chunking) and the **drop-sink moved
  to just before upload** (Parquet fully built, MinIO PUT + Nessie commit skipped).
- Per-file log line present: `Parquet file built (upload skipped)` with `bytes=` and `rows=`.
- Per-CF summary line present: `CF compiled to Parquet (no upload)` with `files=` and `bytes=`.
- Keep the permanent optimisations: RocksDB **compression OFF**, **hardware CRC32**, **fsync OFF**.

### 2.2 Make the knobs settable on the sidecar (injector change)
Today `micewriter-k8s-injector-v1/internal/webhook/injector.go:231–243` hardcodes the sidecar env and
ties `ENGINE_MEM_LIMIT_BYTES` to the container memory limit (line 242). The grid needs:

1. **Pass-through for** `FLUSH_SIZE_BYTES`, `TARGET_PARQUET_BYTES`, `MAX_RETAINED_FROZEN_CFS`.
2. **`ENGINE_MEM_LIMIT_BYTES` decoupled** from `EngineMemLimit` (an optional override), so we can drive
   `concurrent_cf_flushes` *without* changing real RAM (the key cheap lever for outcome B).

Recommended implementation (also useful in production, so not throwaway): have the injector read an
optional set of engine overrides from its **own** env (e.g. `MWENGINE_FLUSH_SIZE_BYTES`,
`MWENGINE_TARGET_PARQUET_BYTES`, `MWENGINE_MAX_RETAINED_FROZEN_CFS`,
`MWENGINE_MEM_LIMIT_BYTES_OVERRIDE`) and overlay them onto the sidecar env map (override-if-present),
leaving the container `limits.memory` independent. Per cell: `kubectl set env` on the injector
Deployment → roll injector → roll sandbox.

> If patching the injector is undesirable, the alternative harness is a dedicated sandbox Deployment
> with the engine sidecar **inlined** (replicate the injector's container: image, env, the
> `/var/run/app` socket `emptyDir`, the `/var/lib/rocksdb` volume, a writable `/tmp` `emptyDir`,
> `readOnlyRootFilesystem`, and remove the `iceberg-stream.micewriter.io/inject` label). Then engine
> env is edited directly on the sandbox Deployment — one roll per cell. Faster iteration, but
> duplicates injector logic; prefer the injector pass-through since those knobs need productionising
> anyway.

---

## 3. Fixed parameters (every cell)

- **Workload: load-test cell 15** — 500 ev/s label, **1 MB** payload (`load_test_events`,
  22 cols: 8×List<Double> + 4×List<Int> + 10×List<String>), **180 s**, **6 sender threads**.
- **Engine CPU limit = 1** (so we measure the constrained case; CPU is already pegged by the IPC
  build at ~1 core).
- Drop-sink before upload (no MinIO/Nessie), compression OFF, HW CRC32, fsync OFF, `ENABLE_MANUAL_FLUSH=true`.
- Baseline references: write-path-IPC drop-before-parquet = **113.2/s, 420 MiB peak** (1 core).
  Adding Parquet encode (SNAPPY) will cost some CPU/throughput — expected, not the thing under test.

`concurrent_cf_flushes = max(1, floor(ENGINE_MEM_LIMIT_BYTES / 256 MiB))`:
`256Mi→1`, `512Mi→2`, `768Mi→3`.

---

## 4. The grid (8 cells)

All values are the **engine sidecar** env / resources for that run. `container mem` = real RAM
(`limits.memory`, the OOM line). `ENGINE_MEM` = `ENGINE_MEM_LIMIT_BYTES` (drives `conc`).

| Cell | FLUSH_SIZE_BYTES | TARGET_PARQUET_BYTES | MAX_RETAINED | ENGINE_MEM (→conc) | container mem | Expected file(s) | Hypothesis / purpose |
|---|---|---|---|---|---|---|---|
| **G1** | 32 Mi | 32 Mi | 8 | 512 Mi (2) | 512 Mi | ~30 MB ×1/CF | **Control** = current behaviour, with Parquet now built. Confirms harness + log parsing. |
| **G2** | 64 Mi | 64 Mi | 4 | 512 Mi (2) | 512 Mi | ~60 MB | Should fit comfortably (baseline 420 + ~2×60 writer). |
| **G3** | 96 Mi | 96 Mi | 2 | 512 Mi (2) | 512 Mi | ~90 MB | **Probe the per-CF ceiling** at default concurrency. Pass here ⇒ outcome A floor. |
| **G4** | 128 Mi | 128 Mi | 2 | 512 Mi (2) | 512 Mi | ~120 MB | Expect **OOM or near** (two ~120 MB writers). Establishes that default conc can't do 128. |
| **G5** | 128 Mi | 128 Mi | 2 | **256 Mi (1)** | 512 Mi | ~120 MB | **KEY cell.** Force `conc=1` via engine self-budget while keeping real 512 MiB. Does 128 fit? Pass ⇒ outcome B. |
| **G6** | 128 Mi | 128 Mi | 2 | 256 Mi (1) | **640 Mi** | ~120 MB | `conc=1` + modest RAM headroom. Comfort margin for 128 MiB. |
| **G7** | 128 Mi | 128 Mi | 2 | **768 Mi (3)** | 768 Mi | ~120 MB | **Counter-demo:** more RAM but `conc=3` → *worse*. Proves memory-bump-alone is the wrong lever; you must cap concurrency. |
| **G8** | 128 Mi | **32 Mi** | 2 | 256 Mi (1) | 512 Mi | ~30 MB ×~4/CF | **Subdivision + memory-decoupling proof:** big CF, small target ⇒ ~4 files of ~32 MB and writer holds only ~T, not the whole CF. The mechanism the persistent writer would generalise across CFs (informs outcome C). |

Notes:
- `get_array_memory_size()` over-estimates, so files emit slightly early ⇒ **actual file ≈ 5–15 %
  below TARGET**. "~120 MB" for a 128 MiB target is expected.
- `batch_bytes` auto-derives from `ENGINE_MEM/100/parser_threads`; at `ENGINE_MEM=256Mi` it shrinks
  to ~2.6 MB — fine, it only smooths parse-stage memory, not file size.
- For single-table `load_test_events`, per-table CF bytes ≈ whole CF, so `file ≈ min(TARGET, FLUSH_SIZE_BYTES)`.

---

## 5. Per-cell procedure

1. **Set engine env** for the cell (injector overrides → roll injector; or inline harness → edit deploy).
   For `container mem` ≠ 512 Mi (G6/G7), also set the sidecar `limits.memory`.
2. **Roll the sandbox** (`kubectl rollout restart deploy/micewriter-sandbox -n micewriter-sandbox`).
   Expect the **SDK UDS startup race** (sandbox boots before engine socket): if the new pod is
   `1/2` with `sandbox` not ready, **delete the pod** so it recreates with the engine image cached.
   Confirm both containers `ready:true` and the engine digest/env are the intended ones.
3. **Capture flamegraph** (optional, only for the cells that pass 128 MiB): port-forward `8088`,
   `GET /debug/pprof/flamegraph?seconds=30` ~40 s into the run.
4. **Launch cell 15:**
   ```
   POST http://k8s-node-1.local/loadtest/sweep
   {"restSecondsBetween":0,"cells":[{"rate":500,"payloadSizeBytes":1048576,"durationSec":180}]}
   ```
   Record `runId`. Wait ~200 s (the `python`-stub trap: parse `status` with `ConvertFrom-Json`, not a Store-stub `python`).
5. **Collect** (section 6), append a row to `results.md`, then proceed to the next cell.

---

## 6. Metrics per cell

**Throughput / latency** — `GET /loadtest/{runId}`: `achievedRate`, `sent`/`failed`, `p50/p95/p99`.

**Parquet file sizes (the headline)** — from engine logs:
```
kubectl logs -n micewriter-sandbox <pod> -c micewriter-engine \
  | Select-String "Parquet file built"     # per-file bytes=
kubectl logs ... | Select-String "CF compiled to Parquet"   # per-CF files= / bytes=
```
Report **min / median / max file bytes** and **files-per-CF**.

**Peak memory & OOM (the constraint)** — Grafana (`grafanacloud-prom`), window = run start→end:
- `max_over_time(container_memory_working_set_bytes{container="micewriter-engine"}[3m])`
- OOM check: `kubectl get pod ... -o jsonpath` on
  `status.containerStatuses[?(@.name=="micewriter-engine")].lastState.terminated.reason` (== `OOMKilled`?)
  and `restartCount`; also `kubectl get events -n micewriter-sandbox | Select-String OOM`.

**Engine CPU** — `rate(container_cpu_usage_seconds_total{container="micewriter-engine"}[2m])`
(expect ~pegged at the 1-core cap; Parquet encode adds to the writer-thread cost).

**Optional** — RocksDB write-stall count from logs; built-file count vs CF count.

---

## 7. Pass / fail per cell

A cell **passes** when **all** hold:
- **No OOMKill** (`restartCount` stays 0, no `OOMKilled` lastState).
- **Peak working-set ≤ 90 %** of `container mem` (headroom for spikes).
- **Median built file size within ~15 %** of `TARGET_PARQUET_BYTES` (accounting for the over-estimate).
- **Throughput not collapsed** — `achievedRate ≥ ~90/s` (soft floor; some drop vs 113/s is expected
  from Parquet encode, but a fall toward the 60/s flush-bound regime means CPU is the new wall —
  note it, don't necessarily fail the cell, since file size + memory are the primary objectives).

---

## 8. Reading the result → outcome

- **G3 passes, G4 OOMs, G5 passes** → **Outcome B (most likely).** 128 MiB is reachable by capping
  `concurrent_cf_flushes` to 1. Ship it by making the engine self-budget / a `CONCURRENT_CF_FLUSHES`
  cap production-configurable. G6 tells you whether a 640 MiB bump is worth the comfort margin.
- **G5 OOMs, G6 passes** → **Outcome B′.** 128 MiB needs conc=1 **and** ~640 MiB; decide if the extra
  128 MiB/pod is acceptable across the fleet.
- **G7 worse than G5/G6** (expected) → confirms "just add RAM" is wrong without a concurrency cap;
  keep `concurrent_cf_flushes` low for large-file workloads regardless.
- **G3 passes but every 128 MiB cell fails affordably** → **Outcome A.** Ship ~96 MiB (Trino-healthy;
  128 was nice-to-have). No persistent writer.
- **You want 128 MiB+ with small CFs/low RAM and G5/G6 can't give it, but G8 cleanly bounds writer
  memory to T** → **Outcome C.** G8 is the green light for the persistent cross-CF writer spec: it
  proves a single writer can emit T-sized files while holding only ~T; the persistent design just
  carries that writer across CF rotations (with the durability rework: defer CF drop until the rows
  are in an emitted+uploaded file).

---

## 9. Cleanup / revert after the grid

- Restore engine env to the chosen production values (the winning cell, or 96 MiB for outcome A).
- If the inline harness was used, re-enable the injector label and delete the harness Deployment.
- Restore engine CPU limit to its production value (the grid runs at CPU=1 deliberately).
- Re-enable the real Stage-4 upload (remove the pre-upload drop-sink) for the production build —
  the drop-sink is a measurement-only build.
- Record the decision (A/B/C) and the chosen `FLUSH_SIZE_BYTES` / `TARGET_PARQUET_BYTES` /
  concurrency / memory in `results.md` and the design docs.

---

## 10. RESULTS & CONCLUSION (run 2026-06-09, engine a576f3ed)

**Outcome: A/B-trivial — 128 MiB Parquet files are achievable at the 512 MiB sidecar with
*default* settings (conc=2). No concurrency cap, no RAM bump, no persistent writer.**

| Cell | FLUSH / TARGET | conc | File max | Peak mem | OOM | Rate |
|---|---|---|---|---|---|---|
| G1 | 32Mi / 32Mi | 2 | ~30 MB | 133 MB | No | 94/s |
| G2 | 64Mi / 64Mi | 2 | ~64 MB | 175 MB | No | 78/s |
| G3 | 96Mi / 96Mi | 2 | ~95 MB | 197 MB | No | 81/s |
| **G4** | **128Mi / 128Mi** | **2** | **~125 MB** | **244 MB (48%)** | **No** | 82/s |
| G5 | 128Mi / 128Mi | 1 | ~126 MB | 267 MB | No | 76/s |
| G8 | 128Mi / 32Mi | 2 | ~32 MB ×4/CF | 200 MB | No | 76/s |

(G6 RAM-bump and G7 conc=3 skipped — moot once G4 passed.)

### Recommended production config (≈128 MiB Trino files)
```
FLUSH_SIZE_BYTES        = 134217728   # 128Mi
TARGET_PARQUET_BYTES    = 134217728   # 128Mi
MAX_RETAINED_FROZEN_CFS = 2
ENGINE_MEM_LIMIT_BYTES  = default 512Mi  (concurrent_cf_flushes=2 — do NOT cap)
```
Peak ~244 MB (48% of 512Mi), ~268 MB headroom.

### Key findings
- **G1 (control) caught a blocker**: the byte threshold accumulated `get_array_memory_size()`, which
  over-counts shared IPC buffers ~25× → every file was 1 row / 1.28 MB. Fixed (engine a576f3ed) to sum
  raw IPC payload bytes. Always run the control first.
- **Memory scales gently** (133→175→197→244, decelerating) — baseline-dominated, not
  `conc × file-size`. The original "128 MiB OOMs at conc=2" prediction was wrong.
- **conc=1 is *worse* than conc=2** (267 > 244 MB): serializing flushes backs up more unflushed CFs
  than it saves in writer memory. Don't cap concurrency.
- **G8 proved `TARGET_PARQUET_BYTES` bounds writer memory to the target, not the CF** (200 MB at 32Mi
  target vs 244 MB at 128Mi, same 128Mi CF). This is the mechanism a cross-CF persistent writer would
  generalize — **shelved as unneeded** since 128 MiB fits per-CF.
- **Cost**: building Parquet (SNAPPY) dropped throughput 113→82/s; SNAPPY on incompressible doubles is
  wasted work. Follow-up: `PARQUET_COMPRESSION=NONE` A/B.

### File-size ceiling
A Parquet file can't exceed one CF's data (`file ≤ FLUSH_SIZE_BYTES`). 128 MiB is comfortable; files
>~150 MiB would need a bigger CF (more backpressure/memory) or the cross-CF persistent writer.
