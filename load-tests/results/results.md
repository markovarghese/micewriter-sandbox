| Scenario | Event size | Rate (ev/s) | Duration | SDK p95 send | Achieved rate | Failed sends | Peak CPU | Peak Mem | OOMKill? | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 10 KB | 10 | 60s | 15.1 ms | 10.0 / s | 0 / 601 | N/A | 14 MB | No | Smoke test |
| 2 | 10 KB | 50 | 60s | 6.2 ms | 50.0 / s | 0 / 3004 | 25m | 43 MB | No | Smoke test |
