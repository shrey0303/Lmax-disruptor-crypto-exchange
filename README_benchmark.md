# Benchmark Methodology and Results

Machine-readable telemetry: [`docs/benchmark/benchmark-results.json`](docs/benchmark/benchmark-results.json)

---

## Environment

| Property | Value |
|---|---|
| CPU | 12 logical cores |
| OS | Windows 11 |
| JDK | OpenJDK 21.0.10+8-LTS (HotSpot 64-Bit Server VM) |
| JMH (§1) | JVM defaults — Java 21 **G1GC**, no heap/GC override |
| Binance replay (§2) | `-Xms4g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch` |

CPU model and total RAM were not captured in the run telemetry.

---

## 1. Matching Core Isolated (JMH)

Measures `OrderBookManager.processOrder()` in isolation. No Disruptor ring buffer, no Kafka, no gRPC, no risk validation, no settlement. Seeds a 300,000-order deep book (3 symbols, 50K bids and 50K asks each). To force absolute worst-case latency and break cache locality, prices are completely randomized across a 10,000-level spread, resulting in a fragmented memory layout across price levels. Each measured operation is two `processOrder` calls: one aggressive (marketable) limit order that sweeps ~2–3 resting price levels, followed by one limit-order replenishment. Aggressor and replenishment quantities follow a Gaussian distribution (mean 50, std 30) clamped at 1-500.

### Configuration

```
@Warmup(iterations = 5, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 10, time = 5, timeUnit = SECONDS)
@Fork(2)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
```

### Results

```text
Benchmark                                                              Mode      Cnt        Score        Error  Units
MatchingEngineBenchmark.benchmarkMatching                             thrpt       20  1700276.309 ± 112883.806  ops/s
MatchingEngineBenchmark.benchmarkMatching                            sample  2468934       ≈ 10⁻⁶                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.00    sample                ≈ 10⁻⁷                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.50    sample                ≈ 10⁻⁶                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.90    sample                ≈ 10⁻⁶                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.95    sample                ≈ 10⁻⁶                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.99    sample                ≈ 10⁻⁶                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.999   sample                ≈ 10⁻⁵                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p0.9999  sample                ≈ 10⁻⁴                s/op
MatchingEngineBenchmark.benchmarkMatching:benchmarkMatching·p1.00    sample                 0.027                s/op
```

| Metric | Value |
|---|---|
| Throughput | 1,700,276 ± 112,884 ops/sec (Cnt=20) |
| Order book depth | 3 symbols × (50K bids + 50K asks) = 300K resting orders |
| Cache topology | Deeply fragmented (10,000 randomized prices) |
| Avg Latency (p50) | ~1 µs |
| Tail Latency (p99) | ~1 µs |
| Tail Latency (p99.9) | ~10 µs |
| Tail Latency (p99.99) | ~100 µs |
| Max observed (p100) | 27 ms (lone JIT/safepoint or G1 young-gen outlier) |
| Operation | 1 aggressive (marketable) limit order sweeping ~2–3 levels + 1 limit replenish |
| GC impact | No collection pauses visible through p99.9 (~10 µs); ~576 B/op (`-prof gc`), all young-gen |

JMH `SampleTime` reports order-of-magnitude buckets, so p50–p99 read identically (~1 µs) at this resolution. Forks run on JVM defaults (Java 21 → **G1GC**); ~576 B/op at 1.7M ops/sec is ~980 MB/s of young-gen churn that never surfaces as tail latency.

---

## 2. Full Pipeline Replay -- 3 Minutes (Binance BTC/USDT + ETH/USDT)

4-stage LMAX Disruptor pipeline: Risk Validation, Noop Journaling, Matching, Settlement. Input is captured Binance BTC/USDT + ETH/USDT WebSocket depth data replayed at maximum producer throughput. Kafka journaling is disabled (`NoopJournaler`); enabling it bounds throughput by the synchronous `send().get()` round-trip.

### Results

| Metric | Value |
|---|---|
| Duration | 3 minutes (182.4s) |
| Total events | 125,322,426 |
| Average throughput | 687,210 ops/sec |
| Post-warmup throughput | 667,825 ops/sec |
| Total places / cancels | 93,636,633 / 31,685,793 |
| Total trades | 61,776 |
| Matching stage p50 | 19.8 ms |
| Matching stage p99 | 78.6 ms |
| Settlement stage p50 | 9.2 ms |
| Settlement stage p99 | 31.3 ms |
| Risk stage p50 | 27.1 ms |
| Risk stage p99 | 86.5 ms |
| Ring buffer residence (E2E) p50 | 65.0 ms |
| Ring buffer residence (E2E) p99 | 158.3 ms |
| Ring buffer residence (E2E) p99.9 | 1,166 ms |
| Dropped events | 0 |
| Parse errors | 0 |
| Order rejection rate | 67.31% (84.36M ÷ 125.32M total events) |
| Match rate | 0.07% (61,776 trades ÷ 93.64M placements) |

Note: 67.31% of all replayed events (84.36M ÷ 125.32M, places + cancels) are risk-rejected due to balance depletion under sustained stress. The 687K ops/sec figure is pipeline throughput. Of events passing risk validation (~33%), most rest in the book without crossing the spread — the 0.07% match rate is trades ÷ placements (61,776 ÷ 93.64M). Post-warmup throughput (667,825) is *lower* than the average (687,210) because the first ~5 s ran against a shallow book with undepleted balances at ~1.37M ops/sec, lifting the average.

Per-handler latencies (Risk 87 ms p99, Match 79 ms p99, Settle 31 ms p99) are computational cost inclusive of ring buffer queueing delay accumulated at each stage under saturation. Percentiles are **not additive** — the p99 of end-to-end residence (158 ms) is not the sum of the per-stage p99s (196 ms); the 1,166 ms p99.9 is the rare deep-queue tail. Under normal operation, matching core latency is sub-microsecond (see JMH).

---

## 3. Reproducing Benchmarks

### JMH

```bash
./gradlew :benchmark:benchmark-cluster-jmh:jmh
```

### Binance Replay

```bash
# Capture fresh data from Binance WebSocket
./gradlew :benchmark:benchmark-binance:run-capture

# Replay at max throughput (noop journaler)
./gradlew :benchmark:benchmark-binance:run-replay

# Replay with Kafka journaling enabled
./gradlew :benchmark:benchmark-binance:run-replay-kafka
```

### gRPC Integration

```bash
docker-compose -f docker-infra.yml up -d
./gradlew :exchange-app:run-leader
./gradlew :benchmark:benchmark-cluster:bootRun

# 500K deposits, 64 concurrent connections
curl -X POST 'http://localhost:8900/api/balance-benchmark/benchmark/500000/64'
```

### ghz (gRPC load generator)

```bash
ghz \
  --insecure \
  --skipFirst 100000 \
  --proto ./exchange-libs/exchange-proto/src/main/proto/trading.proto \
  --call shrey.bank.proto.TradingCommandService/sendCommand \
  -d '{
      "placeOrderCommand": {
          "accountId": 1,
          "symbol": "BTC_USDT",
          "side": "BUY",
          "orderType": "LIMIT",
          "price": 50000,
          "quantity": 100
      }
  }' \
  -c 200 -n 600000 \
  127.0.0.1:9500
```
