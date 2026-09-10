# Collector delivery benchmark

- Redis: 7.4.1 at localhost:6380
- Warm-up: 15s
- Measurement: 60s
- Repetitions: 1
- WAL durability: FileChannel.write only; no force/fsync
- Max mode: producer backpressure at 100,000 outstanding records (sustained throughput, not WAL fill rate)

| Target | Mode | Confirm TPS median (range) | E2E p99 us median (range) | publish() p99 us | Avg batch | Pipeline RTT p99 us | CPU % core | WAL B/record | Drain ms |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 10000 | DIRECT | 4285.3 (4285.3-4285.3) | 517 (517-517) | 518 | 1 | 516 | 31.5 | 0 | 0 |
| 10000 | PIPELINE | 9987.5 (9987.5-9987.5) | 67135 (67135-67135) | 5 | 329.8 | 31951 | 27.5 | 0 | 66.6 |
| 10000 | WAL_PIPELINE | 9991.6 (9991.6-9991.6) | 72255 (72255-72255) | 48 | 348.8 | 31887 | 42.7 | 65 | 32.7 |

## Relative deltas

| Target | PIPELINE vs DIRECT confirm TPS | WAL vs PIPELINE confirm TPS | PIPELINE-DIRECT E2E p99 us | WAL-PIPELINE E2E p99 us |
|---:|---:|---:|---:|---:|
| 10000 | 133.1% | 0% | 66618 | 5120 |

Raw data: `results.csv` and per-run JSON files. `PIPELINE - DIRECT` isolates batching; `WAL_PIPELINE - PIPELINE` isolates synchronous WAL append plus checkpoint cost. E2E starts immediately before publish/enqueue/WAL append and ends only after all Redis XADD replies are confirmed.
