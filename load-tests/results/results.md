| Timestamp (UTC) | Scenario | Event size | Rate (ev/s) | Duration | SDK p95 send | Achieved rate | Failed sends | Peak CPU | Peak Mem | OOMKill? | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-05-31 09:07Z | 1 | 10 KB | 10 | 60s | 15.1 ms | 10.0 / s | 0 / 601 | N/A | 14 MB | No | Smoke test |
| 2026-05-31 09:08Z | 2 | 10 KB | 50 | 60s | 6.2 ms | 50.0 / s | 0 / 3004 | 25m | 43 MB | No | Smoke test |
| 2026-05-31 22:30Z | 3 | 1 KB | 1 | 900s | 9.7 ms | 1.0 / s | 0 / 901 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 4 | 100 KB | 1 | 900s | 11.3 ms | 1.0 / s | 0 / 901 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 5 | 1 MB | 1 | 900s | 17.6 ms | 1.0 / s | 0 / 901 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 6 | 10 MB | 1 | 900s | 36.7 ms | 1.0 / s | 0 / 901 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 7 | 1 KB | 10 | 900s | 12.5 ms | 10.0 / s | 0 / 9001 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 8 | 100 KB | 10 | 900s | 13.6 ms | 10.0 / s | 0 / 9002 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 9 | 1 MB | 10 | 900s | 19.7 ms | 10.0 / s | 0 / 9002 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 10 | 10 MB | 10 | 900s | 20.4 ms | 0.0 / s | 8976 / 9001 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 11 | 1 KB | 100 | 900s | 0.7 ms | 0.0 / s | 90019 / 90019 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 12 | 100 KB | 100 | 900s | 1.1 ms | 0.0 / s | 90014 / 90014 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 13 | 1 MB | 100 | 900s | 4.1 ms | 0.0 / s | 90004 / 90004 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 14 | 1 KB | 500 | 900s | 0.6 ms | 0.0 / s | 450056 / 450056 | N/A | N/A | N/A | |
| 2026-05-31 22:30Z | 15 | 100 KB | 500 | 900s | 0.7 ms | 0.0 / s | 450021 / 450021 | N/A | N/A | N/A | |
| 2026-06-01T06:43:04Z | Cell 12 | 1 KB | 500 | 900 | 0.58ms | 119.78 | 342212 | 104m | 51 MB | No | Backpressure gracefully handled |
| 2026-06-01T06:59:05Z | Cell 8 | 10 MB | 10 | 900 | 60.29ms | 0.72 | 8352 | 374m | 208 MB | No | No OOMKill! Direct I/O successful. |
| 2026-06-02T07:25:36Z | 1 | 1 KB | 1 | 900s | 8.0 ms | 1.0 / s | 0 / 901 | 1m | 7 MB | No | Baseline 1 KB load test completed successfully |
| 2026-06-02T08:16:49Z | Sweep 100KB@1 | 100 KB | 1 | 900s | 8.7 ms | 0.69 / s | 271 / 901 | N/A | N/A | No | No OOMs, graceful backpressure |
| 2026-06-02T08:31:49Z | Sweep 1MB@1 | 1 MB | 1 | 900s | 15.6 ms | 0.38 / s | 558 / 901 | N/A | N/A | No | |
| 2026-06-02T08:47:49Z | Sweep 10MB@1 | 10 MB | 1 | 900s | 22.5 ms | 0.00 / s | 895 / 901 | N/A | N/A | No | |
| 2026-06-02T09:03:49Z | Sweep 1KB@10 | 1 KB | 10 | 900s | 17.6 ms | 10.0 / s | 0 / 9002 | N/A | N/A | No | 100% success rate! |
| 2026-06-02T09:19:50Z | Sweep 100KB@10 | 100 KB | 10 | 900s | 1.2 ms | 0.58 / s | 8477 / 9002 | N/A | N/A | No | |
| 2026-06-02T09:35:50Z | Sweep 1MB@10 | 1 MB | 10 | 900s | 5.4 ms | 0.14 / s | 8870 / 9002 | N/A | N/A | No | |
| 2026-06-02T09:51:50Z | Sweep 10MB@10 | 10 MB | 10 | 900s | 21.4 ms | 0.01 / s | 8983 / 9001 | N/A | N/A | No | |
| 2026-06-02T10:07:50Z | Sweep 1KB@100 | 1 KB | 100 | 900s | 0.7 ms | 19.0 / s | 72818 / 90008 | N/A | N/A | No | Max achievable throughput for 1KB is ~19/s here |
| 2026-06-02T10:23:50Z | Sweep 100KB@100 | 100 KB | 100 | 900s | 0.9 ms | 1.2 / s | 88914 / 90011 | N/A | N/A | No | |
| 2026-06-02T10:39:51Z | Sweep 1MB@100 | 1 MB | 100 | 900s | 4.3 ms | 0.03 / s | 89973 / 90002 | N/A | N/A | No | |
| 2026-06-02T10:55:51Z | Sweep 1KB@500 | 1 KB | 500 | 900s | 0.6 ms | 53.5 / s | 401867 / 450043 | N/A | N/A | No | Saturated at ~53/s, graceful degradation |
| 2026-06-02T11:11:51Z | Sweep 100KB@500 | 100 KB | 500 | 900s | 0.7 ms | 0.32 / s | 449737 / 450027 | N/A | N/A | No | |
