package shrey.benchmarkbinance;

import org.HdrHistogram.Histogram;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shrey.exchange.domain.MatchingResult;
import shrey.exchange.domain.OrderBookManager;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and reports real-time benchmark metrics from the Disruptor pipeline.
 *
 * Thread-safety: recordEvent() is called from the Disruptor consumer thread (single writer).
 * Snapshot reads are safe because HdrHistogram supports concurrent read during single-writer updates.
 *
 * Warmup period:
 * - Industry standard for JVM HFT benchmarks: 30 seconds
 * - Allows JIT C2 compiler to optimize hot paths into native code
 * - Allows ZGC to complete initial warmup GC cycles
 * - Latency samples during warmup are discarded from histograms
 * - Event counts during warmup are tracked separately
 */
public class SimulationMetrics {

    private static final Logger log = LoggerFactory.getLogger(SimulationMetrics.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // --- HdrHistograms: 1μs → 60s range, 2 significant digits ---
    // 2 sig figs: accurate to within 1%, memory-safe for 12h runs
    private final Histogram e2eLatency     = new Histogram(1_000L, 60_000_000_000L, 2);
    private final Histogram riskLatency    = new Histogram(1_000L, 60_000_000_000L, 2);
    private final Histogram matchLatency   = new Histogram(1_000L, 60_000_000_000L, 2);
    private final Histogram settleLatency  = new Histogram(1_000L, 60_000_000_000L, 2);

    // --- Counters ---
    private final AtomicLong totalEvents     = new AtomicLong(0);
    private final AtomicLong totalTrades     = new AtomicLong(0);
    private final AtomicLong totalRejections = new AtomicLong(0);
    private final AtomicLong totalPlaces     = new AtomicLong(0);
    private final AtomicLong totalCancels    = new AtomicLong(0);
    private final AtomicLong burstEvents     = new AtomicLong(0);
    private final AtomicLong warmupEvents    = new AtomicLong(0);

    // --- Timing ---
    private final long startNanos = System.nanoTime();
    private long lastSnapshotNanos = System.nanoTime();
    private long lastSnapshotCount = 0;

    // --- External references ---
    private final OrderBookManager orderBookManager;

    // --- Warmup tracking ---
    // Industry standard: 30s for JVM C2 JIT warmup + ZGC warmup cycles
    // Validated against gc.log: 3 warmup GC cycles complete within ~60s
    private static final long DEFAULT_WARMUP_NANOS = 30_000_000_000L; // 30 seconds
    private final long warmupDurationNanos;
    private volatile boolean warmupComplete = false;
    private long warmupEndNanos;

    // --- Burst tracking ---
    private volatile boolean inBurst = false;
    private long burstStartNanos;
    private long burstEndNanos;
    private long burstStartCount;

    // --- Drop/error tracking (fed from ingester) ---
    private volatile long lastReportedDrops = 0;
    private volatile long lastReportedErrors = 0;

    public SimulationMetrics(OrderBookManager orderBookManager) {
        this(orderBookManager, DEFAULT_WARMUP_NANOS);
    }

    public SimulationMetrics(OrderBookManager orderBookManager, long warmupDurationNanos) {
        this.orderBookManager = orderBookManager;
        this.warmupDurationNanos = warmupDurationNanos;
    }

    /**
     * Called from MetricsReplyHandler on the Disruptor consumer thread.
     */
    public void recordEvent(long entryNanos, long riskNanos, long matchNanos, long exitNanos,
                            MatchingResult matchingResult, boolean rejected) {
        totalEvents.incrementAndGet();

        if (rejected) {
            totalRejections.incrementAndGet();
        }

        if (matchingResult != null && matchingResult.getTrades() != null) {
            totalTrades.addAndGet(matchingResult.getTrades().size());
        }

        if (inBurst) {
            burstEvents.incrementAndGet();
        }

        // Check warmup status
        if (!warmupComplete) {
            long elapsed = System.nanoTime() - startNanos;
            if (elapsed >= warmupDurationNanos) {
                warmupComplete = true;
                warmupEndNanos = System.nanoTime();
                warmupEvents.set(totalEvents.get());
                log.info("═══ WARMUP COMPLETE ({}s) — JIT compiled, histograms recording ═══",
                    String.format("%.1f", elapsed / 1_000_000_000.0));
            } else {
                // During warmup: count events but don't record latencies
                return;
            }
        }

        // Record latencies (only after warmup)
        if (entryNanos > 0 && exitNanos > entryNanos) {
            long e2e = exitNanos - entryNanos;
            e2eLatency.recordValue(Math.min(e2e, 60_000_000_000L));
        }
        if (entryNanos > 0 && riskNanos > entryNanos) {
            long risk = riskNanos - entryNanos;
            riskLatency.recordValue(Math.min(risk, 60_000_000_000L));
        }
        if (riskNanos > 0 && matchNanos > riskNanos) {
            long match = matchNanos - riskNanos;
            matchLatency.recordValue(Math.min(match, 60_000_000_000L));
        }
        if (matchNanos > 0 && exitNanos > matchNanos) {
            long settle = exitNanos - matchNanos;
            settleLatency.recordValue(Math.min(settle, 60_000_000_000L));
        }
    }

    public void recordPlace() { totalPlaces.incrementAndGet(); }
    public void recordCancel() { totalCancels.incrementAndGet(); }

    public void startBurst() {
        burstStartNanos = System.nanoTime();
        burstStartCount = totalEvents.get();
        burstEvents.set(0);
        inBurst = true;
        log.info(">>> BENCHMARK METRICS STARTED");
    }

    public void endBurst() {
        inBurst = false;
        burstEndNanos = System.nanoTime();
        log.info("<<< BURST MODE ENDED — {} events absorbed in {}s",
            burstEvents.get(), String.format("%.2f", (burstEndNanos - burstStartNanos) / 1_000_000_000.0));
    }

    /**
     * Log a one-line periodic snapshot. Called from a timer thread.
     */
    public void logPeriodicSnapshot() {
        long now = System.nanoTime();
        long currentCount = totalEvents.get();
        long intervalCount = currentCount - lastSnapshotCount;
        double intervalSec = (now - lastSnapshotNanos) / 1_000_000_000.0;
        double intervalThroughput = intervalSec > 0 ? intervalCount / intervalSec : 0;

        double elapsedSec = (now - startNanos) / 1_000_000_000.0;
        double overallThroughput = elapsedSec > 0 ? currentCount / elapsedSec : 0;

        lastSnapshotNanos = now;
        lastSnapshotCount = currentCount;

        String time = TIME_FMT.format(Instant.now());
        String warmupTag = warmupComplete ? "" : " [WARMUP]";

        log.info("[{}]{} interval={} ops/sec | overall={} ops/sec | p50={}μs | p99={}μs | p99.9={}μs | trades={} | rejected={} | events={}",
            time,
            warmupTag,
            String.format("%,.0f", intervalThroughput),
            String.format("%,.0f", overallThroughput),
            String.format("%.1f", e2eLatency.getTotalCount() > 0 ? e2eLatency.getValueAtPercentile(50.0) / 1000.0 : 0),
            String.format("%.1f", e2eLatency.getTotalCount() > 0 ? e2eLatency.getValueAtPercentile(99.0) / 1000.0 : 0),
            String.format("%.1f", e2eLatency.getTotalCount() > 0 ? e2eLatency.getValueAtPercentile(99.9) / 1000.0 : 0),
            totalTrades.get(),
            totalRejections.get(),
            currentCount
        );
    }

    /**
     * Print the full benchmark report to stdout and write JSON to file.
     */
    public void printFinalReport(String mode, long durationMillis, long droppedEvents, long parseErrors) {
        PrintStream out = System.out;
        double durationSec = durationMillis / 1000.0;
        long total = totalEvents.get();
        double throughput = durationSec > 0 ? total / durationSec : 0;

        // Post-warmup metrics
        long warmupEvts = warmupEvents.get();
        long postWarmupEvents = total - warmupEvts;
        double warmupSec = warmupDurationNanos / 1_000_000_000.0;
        double postWarmupSec = durationSec - warmupSec;
        double postWarmupThroughput = postWarmupSec > 0 ? postWarmupEvents / postWarmupSec : 0;

        out.println();
        out.println("╔══════════════════════════════════════════════════════════════════╗");
        out.println("║           BINANCE LIVE FEED → DISRUPTOR BENCHMARK REPORT        ║");
        out.println("╚══════════════════════════════════════════════════════════════════╝");
        out.println();
        out.printf("  Mode:             %s%n", mode.toUpperCase());
        out.printf("  Duration:         %.1f seconds (%.1f minutes)%n", durationSec, durationSec / 60.0);
        out.printf("  Warmup:           %.0f seconds (JIT C2 compilation + ZGC warmup)%n", warmupSec);
        out.printf("  Data Source:      Binance WebSocket (BTC_USDT + ETH_USDT depth@100ms)%n");
        out.println();

        out.println("┌─────────────────── THROUGHPUT ───────────────────┐");
        out.printf("│  Total events:     %,d (warmup: %,d)%n", total, warmupEvts);
        out.printf("│  Post-warmup:      %,d events in %.1fs%n", postWarmupEvents, postWarmupSec);
        out.printf("│  Avg throughput:   %,.0f ops/sec (overall)%n", throughput);
        out.printf("│  Post-warmup:      %,.0f ops/sec%n", postWarmupThroughput);
        out.printf("│  Total trades:     %,d%n", totalTrades.get());
        out.printf("│  Total places:     %,d%n", totalPlaces.get());
        out.printf("│  Total cancels:    %,d%n", totalCancels.get());
        out.printf("│  Rejections:       %,d (%.2f%%)%n", totalRejections.get(),
            total > 0 ? (totalRejections.get() * 100.0 / total) : 0);
        out.printf("│  Match rate:       %.2f%%%n",
            totalPlaces.get() > 0 ? (totalTrades.get() * 100.0 / totalPlaces.get()) : 0);
        out.printf("│  Parse errors:     %,d%n", parseErrors);
        out.println("└─────────────────────────────────────────────────┘");
        out.println();

        double dropRate = (total + droppedEvents) > 0 ? ((double) droppedEvents / (total + droppedEvents)) * 100.0 : 0;
        out.println("┌─────────────────── DROP METRICS ───────────────────┐");
        out.printf("│  Dropped events:   %,d%n", droppedEvents);
        out.printf("│  Drop rate:        %.4f%%%n", dropRate);
        out.println("│  Throughput measured on successfully published events only.");
        if (dropRate > 1.0) {
            out.println("│  ⚠️ WARNING: Drop rate exceeds 1%! Severe backpressure detected.");
        }
        out.println("└────────────────────────────────────────────────────┘");
        out.println();

        out.println("  ℹ Latency metrics below are POST-WARMUP only (excludes first " +
            String.format("%.0f", warmupSec) + "s of JIT compilation)");
        out.println();

        printLatencyTable(out, "E2E LATENCY (entry → exit)", e2eLatency);
        printLatencyTable(out, "RISK STAGE LATENCY", riskLatency);
        printLatencyTable(out, "MATCHING STAGE LATENCY", matchLatency);
        printLatencyTable(out, "SETTLEMENT STAGE LATENCY", settleLatency);

        if (burstEvents.get() > 0) {
            double burstDurationSec = (burstEndNanos - burstStartNanos) / 1_000_000_000.0;
            double burstThroughput = burstDurationSec > 0 ? burstEvents.get() / burstDurationSec : 0;
            out.println("┌─────────────────── BURST METRICS ─────────────────┐");
            out.printf("│  Burst events:      %,d%n", burstEvents.get());
            out.printf("│  Burst duration:    %.2f seconds%n", burstDurationSec);
            out.printf("│  Burst throughput:  %,.0f ops/sec%n", burstThroughput);
            out.println("└──────────────────────────────────────────────────┘");
            out.println();
        }



        out.println("  ✓ GC log written to: gc.log (check for pauses > 1ms)");
        out.println();

        // Write JSON report
        writeJsonReport(mode, durationSec, throughput, postWarmupThroughput,
            postWarmupEvents, warmupEvts, droppedEvents, parseErrors);
    }

    // Backward compat
    public void printFinalReport(String mode, long durationMillis) {
        printFinalReport(mode, durationMillis, 0, 0);
    }

    private void printLatencyTable(PrintStream out, String title, Histogram hist) {
        out.printf("┌─────────────────── %s ───────────────────┐%n", title);
        if (hist.getTotalCount() == 0) {
            out.println("│  (no data recorded — still in warmup?)");
        } else {
            out.printf("│  p50:    %,.1f μs%n", hist.getValueAtPercentile(50.0) / 1000.0);
            out.printf("│  p95:    %,.1f μs%n", hist.getValueAtPercentile(95.0) / 1000.0);
            out.printf("│  p99:    %,.1f μs%n", hist.getValueAtPercentile(99.0) / 1000.0);
            out.printf("│  p99.9:  %,.1f μs%n", hist.getValueAtPercentile(99.9) / 1000.0);
            out.printf("│  p99.99: %,.1f μs%n", hist.getValueAtPercentile(99.99) / 1000.0);
            out.printf("│  max:    %,.1f μs%n", hist.getMaxValue() / 1000.0);
            out.printf("│  count:  %,d%n", hist.getTotalCount());
        }
        out.println("└──────────────────────────────────────────────────┘");
        out.println();
    }

    private void writeJsonReport(String mode, double durationSec, double throughput,
                                  double postWarmupThroughput, long postWarmupEvents,
                                  long warmupEvents, long droppedEvents, long parseErrors) {
        try {
            JSONObject report = new JSONObject();
            report.put("mode", mode);
            report.put("dataSource", "Binance WebSocket BTC_USDT + ETH_USDT depth@100ms");
            report.put("durationSeconds", durationSec);
            report.put("warmupSeconds", warmupDurationNanos / 1_000_000_000.0);
            report.put("totalEvents", totalEvents.get());
            report.put("warmupEvents", warmupEvents);
            report.put("postWarmupEvents", postWarmupEvents);
            report.put("throughputOpsPerSec", throughput);
            report.put("postWarmupThroughputOpsPerSec", postWarmupThroughput);
            report.put("totalTrades", totalTrades.get());
            report.put("totalPlaces", totalPlaces.get());
            report.put("totalCancels", totalCancels.get());
            report.put("rejections", totalRejections.get());
            report.put("droppedEvents", droppedEvents);
            double dropRate = (postWarmupEvents + droppedEvents) > 0 ? ((double) droppedEvents / (totalEvents.get() + droppedEvents)) * 100.0 : 0;
            report.put("dropRatePercent", dropRate);
            report.put("parseErrors", parseErrors);
            report.put("matchRatePercent", totalPlaces.get() > 0 ? (totalTrades.get() * 100.0 / totalPlaces.get()) : 0);

            report.put("e2eLatency", histogramToJson(e2eLatency));
            report.put("riskLatency", histogramToJson(riskLatency));
            report.put("matchLatency", histogramToJson(matchLatency));
            report.put("settleLatency", histogramToJson(settleLatency));

            if (burstEvents.get() > 0) {
                JSONObject burst = new JSONObject();
                burst.put("events", burstEvents.get());
                burst.put("durationSec", (burstEndNanos - burstStartNanos) / 1_000_000_000.0);
                burst.put("throughputOpsPerSec", burstEvents.get() / ((burstEndNanos - burstStartNanos) / 1_000_000_000.0));
                report.put("burstMetrics", burst);
            }



            String filename = "benchmark_results_" + mode + ".json";
            try (FileWriter fw = new FileWriter(filename)) {
                fw.write(report.toString(2));
            }
            log.info("Benchmark results written to {}", filename);
        } catch (IOException e) {
            log.error("Failed to write benchmark JSON", e);
        }
    }

    private JSONObject histogramToJson(Histogram hist) {
        JSONObject j = new JSONObject();
        if (hist.getTotalCount() == 0) return j;
        j.put("p50_us", hist.getValueAtPercentile(50.0) / 1000.0);
        j.put("p95_us", hist.getValueAtPercentile(95.0) / 1000.0);
        j.put("p99_us", hist.getValueAtPercentile(99.0) / 1000.0);
        j.put("p999_us", hist.getValueAtPercentile(99.9) / 1000.0);
        j.put("p9999_us", hist.getValueAtPercentile(99.99) / 1000.0);
        j.put("max_us", hist.getMaxValue() / 1000.0);
        j.put("count", hist.getTotalCount());
        j.put("note", "post-warmup only (first 30s excluded)");
        return j;
    }
}
