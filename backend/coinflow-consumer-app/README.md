# CoinFlow Consumer Logic Analysis

## 1. Logic Flow (Tick Data Consumption to Aggregation)

The system follows a **Stream-based In-Memory Aggregation** pattern with a scheduled flush mechanism.

### A. Ingestion Phase
1. **Source**: Redis Stream (`tick:raw`)
2. **Consumer**: `TickRawEventConsumer` implements `StreamListener`.
3. **Handler**: `TickRawMessageHandler` converts the raw Map payload into a `TickRawEvent` (Symbol, Price, Quantity, EventTime).
4. **Service**: `TickProcessService` delegates processing to the specific aggregation service (`Ohlc1mAggregationService`).

### B. Aggregation Phase (In-Memory)
- **Store**: `Ohlc1mAggregationStore` maintains a `ConcurrentHashMap<AggregateKey, OhlcAccumulator>`.
- **Accumulator**: `OhlcAccumulator` updates OHLC data in memory.
    - **Logic**:
        - `Open`: First price received.
        - `High/Low`: Max/Min price comparison.
        - `Close`: Price of the event with the latest `eventTime`.
        - `Volume`: Cumulative sum.
    - **Concurrency**: Thread safety is achieved via `ConcurrentHashMap.compute()`, which locks the key (Symbol + Bucket) during processing.

### C. Persistence Phase (Flush)
- **Scheduler**: `Ohlc1mFlushScheduler` runs every **1000ms**.
- **Close Check**: Iterates through all keys in memory and checks if the bucket time window has passed using `BucketCloseChecker`.
- **Flush Action**:
    1. Calls `Ohlc1mFlushService.flush()`.
    2. Saves to DB via `Ohlc1mService.applyAndSave()`.
    3. Publishes `Ohlc1mFlushedEvent` for downstream processing (e.g., 5m/30m rollup).
    4. Removes the key from memory.

## 2. Package & Dependency Analysis

The module structure separates concerns effectively but has tight coupling with Core.

- **`com.coinflow.consumer`**: Clean separation of Redis connectivity.
- **`com.coinflow.aggregation`**: Contains the core business logic for this module (Accumulator, Store, Scheduler).
- **Dependencies**:
    - **`:coinflow-core`**: 
        - Used for Domain Entities (`Symbol`, `Ohlc`) and Services (`SymbolService`, `Ohlc1mService`).
        - **Observation**: The consumer application directly depends on Core Domain Services. While this is typical in a modular monolith, it means the consumer is tightly bound to the Core's database transaction/entity structure.
    - **`:coinflow-common`**: Used for DTOs and Event definitions.

## 3. Suggestions for Improvement

### A. Scalability of Scheduler Loop
- **Current**: The Scheduler iterates **all active keys** every second.
- **Risk**: As the number of symbols grows (e.g., > 10,000 pairs), this "Stop-the-world" style iteration can slow down.
- **Improvement**: Use a **DelayQueue** or a `TimeWindow`-based partitioning map to only check buckets that are actually due for closure.

### B. High Contention on Hot Symbols
- **Current**: `store.compute()` locks the entire entry for a symbol.
- **Risk**: For a symbol with extremely high frequency ticks (e.g., 1000+ TPS), the lock contention might become a bottleneck.
- **Improvement**: 
    - Use **Double Buffering** or **LongAdder** implementations for volume.
    - Or shard the accumulator internally if contention becomes proven.

### C. Memory Safety
- **Current**: Implicit reliance on successful flush to clear memory.
- **Risk**: If the DB goes down and flush fails, the map will grow indefinitely until OOM.
- **Improvement**: Implement a **Backpressure** mechanism or a `max-size` eviction policy (possibly spilling to disk or pausing consumption).

### D. Late Event Handling
- **Current**: If a late tick arrives after flush, it creates a new accumulator which is then immediately flushed next cycle.
- **Implication**: `Ohlc1mService` **must** support Upsert (Update on Conflict) logic to merge this late data correctly with the already saved record. (Need to verify Core implementation for this).
