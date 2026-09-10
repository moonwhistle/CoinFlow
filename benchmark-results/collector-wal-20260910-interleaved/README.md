# Collector delivery benchmark

- Redis: 7.4.1 at localhost:6380
- Warm-up: 15s
- Measurement: 60s
- Repetitions: 3
- WAL durability: `FileChannel.write()` only; no `force()`/`fsync`
- Max mode: producer backpressure at 100,000 outstanding records (sustained throughput, not WAL fill rate)
- Runtime: Windows JVM -> WSL Redis. Docker Desktop was unavailable, so this run used Redis 7.4.1 rather than the planned 7.2.
- Redis background controls: AOF `everysec`; RDB snapshots and automatic AOF rewrite disabled to avoid per-mode fork/rewrite bias.
- Mode order rotated per repetition: D/P/W, P/W/D, W/D/P.

| Target | Mode | Confirm TPS median (range) | E2E p99 us median (range) | publish() p99 us | Avg batch | Pipeline RTT p99 us | CPU % core | WAL B/record | Drain ms |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| max | DIRECT | 2408.2 (2088.3-2605.3) | 1146 (1100-1170) | 1151 | 1 | 1141 | 32.1 | 0 | 0 |
| max | PIPELINE | 19227.2 (18562.5-21271.9) | 9068543 (5058559-9461759) | 1 | 499.8 | 61887 | 71.1 | 0 | 5032.5 |
| max | WAL_PIPELINE | 9973.8 (7917.7-16516) | 12263423 (6365183-13058047) | 61 | 499.8 | 70847 | 92.6 | 65 | 12166.2 |
| 1000 | DIRECT | 999.9 (999.9-1000) | 1671 (1660-1712) | 1677 | 1 | 1660 | 20.3 | 0 | 0 |
| 1000 | PIPELINE | 999.4 (999.1-999.6) | 78719 (77311-88063) | 44 | 32 | 34591 | 24.4 | 0 | 26.4 |
| 1000 | WAL_PIPELINE | 999 (999-999.2) | 92735 (84287-93439) | 1589 | 37.5 | 33951 | 38.7 | 65 | 47.8 |
| 5000 | DIRECT | 2455 (1906.9-2501.4) | 1163 (1163-1257) | 1166 | 1 | 1160 | 29.7 | 0 | 0 |
| 5000 | PIPELINE | 4994.9 (4994.1-4996.7) | 93951 (92351-108991) | 10 | 200.9 | 45087 | 39.1 | 0 | 52.2 |
| 5000 | WAL_PIPELINE | 4993.9 (4991-4995.5) | 127935 (99647-152703) | 151 | 246.3 | 54655 | 55.7 | 65 | 59 |
| 10000 | DIRECT | 2565.5 (2535.4-2653.2) | 1077 (983-1100) | 1080 | 1 | 1074 | 30.5 | 0 | 0 |
| 10000 | PIPELINE | 9990.8 (9982.1-9992) | 91391 (91199-92607) | 7 | 421.6 | 50047 | 48.7 | 0 | 40.2 |
| 10000 | WAL_PIPELINE | 9985 (9984.8-9986) | 114879 (108543-149887) | 87 | 481.1 | 49439 | 68.4 | 65 | 71.1 |

## Relative deltas

| Target | PIPELINE vs DIRECT confirm TPS | WAL vs PIPELINE confirm TPS | PIPELINE-DIRECT E2E p99 us | WAL-PIPELINE E2E p99 us |
|---:|---:|---:|---:|---:|
| max | +698.4% | -48.1% | +9067397 | +3194880 |
| 1000 | -0.0% | -0.0% | +77048 | +14016 |
| 5000 | +103.5% | -0.0% | +92788 | +33984 |
| 10000 | +289.4% | -0.1% | +90314 | +23488 |

## Recovery verification

- Process halt after 1,000 WAL appends and before XADD: 1,000 unique records, missing 0, duplicates 0 after restart.
- Process halt after 1,000 XADD successes and before checkpoint: 1,000 unique records, missing 0, duplicates 1,000 after restart.
- Redis stopped for about 15 seconds while accepting 29,997 ticks: accepted/confirmed 29,997, retries 8, final pending records/bytes 0. Stream length including warm-up was exactly 30,991.
- Unit tests cover v1/v2 codec, invalid versions, segment rotation/deletion, checkpoint checksum, partial/corrupt tail recovery, capacity blocking/readiness, batch size/interval, and ambiguous-response whole-batch retry.

## Interpretation

- At 5k and 10k target rates, `WAL_PIPELINE` preserved `PIPELINE` throughput: median confirmed TPS differed by less than 0.1%, while caller-side p99 increased by about 141 us at 5k and 80 us at 10k.
- At 10k, median process CPU rose from 48.7% to 68.4% of one core. WAL storage was consistently 65 bytes per v2 tick.
- The unlimited test exposed the saturation trade-off: median confirmed throughput was 19.2k for `PIPELINE` and 10.0k for `WAL_PIPELINE`, with wide ranges caused by Redis/WSL RTT drift. Treat this as a capacity-planning warning, not a production limit.
- Low-rate batching materially increases latency because the 10 ms flush wait and Redis pipeline RTT dominate. `DIRECT` remains appropriate when sub-millisecond delivery latency matters more than throughput and process-crash replay.
- This WAL is process-crash recovery only. It deliberately makes no power/OS/disk-loss durability claim because neither WAL nor checkpoint calls `force()`/`fsync`.
- Consumer v2 dedupe remains a one-minute in-memory Caffeine cache keyed by `symbol:tradeId`; it is not durable exactly-once processing.

## Redis and WAL metric supplement

The full matrix was preserved unchanged. After adding Redis `INFO` CPU/network capture and the WAL append timer export, a separate one-run 10k TPS supplement produced:

| Mode | Confirm TPS | Redis CPU % core | Redis input B/s | Redis output B/s | WAL append p50/p95/p99 us |
|---|---:|---:|---:|---:|---:|
| DIRECT | 4285.3 | 17.59 | 677084 | 94310 | - |
| PIPELINE | 9987.5 | 12.56 | 1602480 | 232654 | - |
| WAL_PIPELINE | 9991.6 | 12.28 | 1642867 | 232506 | 2.37 / 7.62 / 36.8 |

The supplement is a single run and is not substituted into the three-run matrix medians. Its purpose is to verify the additional instrumentation and isolate the actual WAL append timer from payload encoding and metric-call overhead.

## Decision

The experiment supports keeping `DIRECT` as the default and exposing `WAL_PIPELINE` for a canary where raw-trade loss on Collector process failure is unacceptable. Up to the tested 10k TPS target, WAL bought replay with negligible confirmed-throughput loss and moderate CPU/caller-latency cost. Before making it the global default, repeat the maximum-throughput run on the actual Redis 7.2 deployment host and validate that its sustained headroom exceeds peak traffic.

Raw data: `results.csv` and per-run JSON files. `PIPELINE - DIRECT` isolates batching; `WAL_PIPELINE - PIPELINE` isolates synchronous WAL append plus checkpoint cost. E2E starts immediately before publish/enqueue/WAL append and ends only after all Redis XADD replies are confirmed.
