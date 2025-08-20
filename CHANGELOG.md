## v2.0.0 — Full Trading Platform
- Allocation-light order book with intrusive linked-list price levels (Agrona `Long2ObjectHashMap`); no GC pauses observed during steady-state measurement (not fully zero-allocation — ~576 B/op)
- Price-time priority matching engine (limit, market, partial fills)
- 4-stage LMAX Disruptor pipeline: Risk Validation → Kafka Journaling → Matching → Settlement
- Pre-trade risk management with position limits and circuit breaker
- Multi-asset trading wallets with hold/release/settle lifecycle
- HdrHistogram microsecond latency profiling (P50/P95/P99/Max)
- WebSocket real-time market data and latency feeds
- Event replay system (rebuild order book from Kafka)
- Prometheus metrics + Grafana dashboard
- Load simulator for stress testing
- JMH benchmark suite: **1,700,276 ops/sec** (each op = one aggressive match + one replenishment, i.e. ~3.4M `processOrder` calls/sec); full pipeline sustains ~407K ops/sec steady-state under Binance replay
- Live dark-mode trading dashboard (HTML)
- Market data gRPC streaming service

## v1.0.0 — Exchange Core
- Core LMAX Disruptor architecture
- gRPC transport layer
- Kafka event sourcing
- PostgreSQL snapshot persistence
- Leader/Follower/Learner cluster topology
