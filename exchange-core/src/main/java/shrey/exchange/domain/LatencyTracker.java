package shrey.exchange.domain;

import org.HdrHistogram.Histogram;

public class LatencyTracker {

    // 1 microsecond to 60 seconds, 2 significant digits
    // 60s max handles extreme queueing latency during max-throughput replay benchmarks
    // 2 sig figs keeps memory footprint low — still accurate to within 1% for percentiles
    private static final long MAX_TRACKABLE_NS = 60_000_000_000L;
    private final Histogram e2eLatency = new Histogram(1000L, MAX_TRACKABLE_NS, 2);
    private final Histogram riskLatency = new Histogram(1000L, MAX_TRACKABLE_NS, 2);
    private final Histogram matchLatency = new Histogram(1000L, MAX_TRACKABLE_NS, 2);
    private final Histogram settleLatency = new Histogram(1000L, MAX_TRACKABLE_NS, 2);

    public void recordLatencies(long entryNanos, long riskNanos, long matchNanos, long settleNanos) {
        if (entryNanos == 0) return;
        
        if (riskNanos > entryNanos) {
            riskLatency.recordValue(Math.min(riskNanos - entryNanos, MAX_TRACKABLE_NS));
        }
        if (matchNanos > riskNanos) {
            matchLatency.recordValue(Math.min(matchNanos - riskNanos, MAX_TRACKABLE_NS));
        }
        if (settleNanos > matchNanos) {
            settleLatency.recordValue(Math.min(settleNanos - matchNanos, MAX_TRACKABLE_NS));
        }
        if (settleNanos > entryNanos) {
            e2eLatency.recordValue(Math.min(settleNanos - entryNanos, MAX_TRACKABLE_NS));
        }
    }

    public LatencyStats getStats() {
        return new LatencyStats(
            getPercentiles(e2eLatency),
            getPercentiles(riskLatency),
            getPercentiles(matchLatency),
            getPercentiles(settleLatency)
        );
    }

    private LatencyStats.Percentiles getPercentiles(Histogram histogram) {
        if (histogram.getTotalCount() == 0) {
            return new LatencyStats.Percentiles(0, 0, 0, 0);
        }
        return new LatencyStats.Percentiles(
            histogram.getValueAtPercentile(50.0) / 1000.0, // convert ns to us
            histogram.getValueAtPercentile(95.0) / 1000.0,
            histogram.getValueAtPercentile(99.0) / 1000.0,
            histogram.getValueAtPercentile(99.9) / 1000.0
        );
    }
}
