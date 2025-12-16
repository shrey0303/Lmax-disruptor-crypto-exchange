## v2.0.0 — Full Trading Platform
- GC-free order book with intrusive linked-list price levels (Agrona `Long2ObjectHashMap`)
- Price-time priority matching engine (limit, market, partial fills)
- 4-stage LMAX Disruptor pipeline: Risk Validation → Kafka Journaling → Matching → Settlement
- Pre-trade risk management with position limits and circuit breaker
- Multi-asset trading wallets with hold/release/settle lifecycle
- HdrHistogram microsecond latency profiling (P50/P95/P99/Max)
- WebSocket real-time market data and latency feeds
- Event replay system (rebuild order book from Kafka)
- Prometheus metrics + Grafana dashboard
- Load simulator for stress testing
- JMH benchmark suite: **3,943,456 ops/sec** matching throughput
- Live dark-mode trading dashboard (HTML)
- Market data gRPC streaming service

## v1.0.0 — Exchange Core
- Core LMAX Disruptor architecture
- gRPC transport layer
- Kafka event sourcing
- MySQL snapshot persistence
- Leader/Follower/Learner cluster topology
