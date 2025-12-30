package shrey.benchmarkbinance;

import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shrey.exchange.CommandBufferEvent;
import shrey.exchange.MatchingHandler;
import shrey.exchange.RiskValidationHandler;
import shrey.exchange.SettlementHandler;
import shrey.exchange.domain.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Standalone benchmark runner that connects live Binance WebSocket order book
 * data
 * directly into the LMAX Disruptor matching engine pipeline.
 *
 * Usage:
 * java -cp ... shrey.benchmarkbinance.BinanceBenchmarkRunner --steady (30min baseline)
 * java -cp ... shrey.benchmarkbinance.BinanceBenchmarkRunner --hft (10min 90% cancels)
 *
 * Or via Gradle:
 * ./gradlew :benchmark:benchmark-binance:run-steady
 * ./gradlew :benchmark:benchmark-binance:run-hft
 *
 * Pipeline (mirrors LeaderConfiguration):
 * RiskValidationHandler → NoopJournaler → MatchingHandler → SettlementHandler →
 * MetricsReplyHandler
 *
 * Data source: Binance WebSocket (public, no auth)
 * - BTC_USDT depth@100ms
 * - ETH_USDT depth@100ms
 */
public class BinanceBenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BinanceBenchmarkRunner.class);

    private static final int RING_BUFFER_SIZE = 1 << 16;

    // Account initial balance — realistic amount, topped up periodically to prevent risk exhaustion
    private static final int NUM_ACCOUNTS = 100;
    private static final long USDT_BALANCE = 1_000_000L;
    private static final long BTC_BALANCE = 1_000_000L;
    private static final long ETH_BALANCE = 1_000_000L;

    public static void main(String[] args) {
        String mode = "steady"; // default
        if (args.length > 0) {
            mode = args[0].replace("--", "").toLowerCase();
        }

        log.info("     BINANCE LIVE FEED → LMAX DISRUPTOR BENCHMARK           ");
        log.info("Mode: {}", mode.toUpperCase());
        log.info("Ring Buffer: {} slots", RING_BUFFER_SIZE);
        log.info("JVM: {} {}", System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
        log.info("OS: {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));
        log.info("Available processors: {}", Runtime.getRuntime().availableProcessors());
        log.info("Warmup period: 30s (JIT C2 + ZGC warmup)");
        log.info("GC: ZGC (concurrent, sub-ms pauses)");
        log.info("");

        double cancelRatio;
        long durationMinutes;

        switch (mode) {
            case "hft" -> {
                cancelRatio = 0.9;
                durationMinutes = 10;
                log.info("HFT MODE: 90% cancel ratio for {} minutes", durationMinutes);
            }
            default -> {
                // steady
                mode = "steady";
                cancelRatio = 0.0;
                durationMinutes = 30;
                log.info("STEADY MODE: Live 1x feed for {} minutes", durationMinutes);
            }
        }

        try {
            runBenchmark(mode, cancelRatio, durationMinutes);
        } catch (Exception e) {
            log.error("Benchmark failed", e);
            System.exit(1);
        }
    }

    private static void runBenchmark(String mode, double cancelRatio, long durationMinutes)
            throws Exception {

        log.info("Setting up domain components...");
        TradingWallets wallets = new TradingWallets();
        CircuitBreaker circuitBreaker = new CircuitBreaker();
        RiskEngine riskEngine = new RiskEngine(circuitBreaker);
        OrderBookManager orderBookManager = new OrderBookManager();
        LatencyTracker latencyTracker = new LatencyTracker();
        SimulationMetrics metrics = new SimulationMetrics(orderBookManager);

        log.info("Pre-seeding {} accounts ({}M USDT, {}M BTC, {}M ETH each)...",
                NUM_ACCOUNTS,
                USDT_BALANCE / 1_000_000L,
                BTC_BALANCE / 1_000_000L,
                ETH_BALANCE / 1_000_000L);

        final long[] verifiedAccountIds = new long[NUM_ACCOUNTS];
        for (int i = 0; i < NUM_ACCOUNTS; i++) {
            long id = wallets.newTradingAccount();
            verifiedAccountIds[i] = id;
            wallets.deposit(id, "USDT", USDT_BALANCE);
            wallets.deposit(id, "BTC", BTC_BALANCE);
            wallets.deposit(id, "ETH", ETH_BALANCE);
        }

        log.info("Building Disruptor pipeline (RingBuffer={}, WaitStrategy=Yielding)...", RING_BUFFER_SIZE);

        RiskValidationHandler riskHandler = new RiskValidationHandler(wallets, riskEngine);
        NoopJournaler noopJournaler = new NoopJournaler();
        MatchingHandler matchingHandler = new MatchingHandler(orderBookManager);
        SettlementHandler settlementHandler = new SettlementHandler(wallets, riskEngine, circuitBreaker,
                latencyTracker);
        MetricsReplyHandler metricsReply = new MetricsReplyHandler(metrics);

        Disruptor<CommandBufferEvent> disruptor = new Disruptor<>(
                CommandBufferEvent::new,
                RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy());

        disruptor.handleEventsWith(riskHandler)
                .then(noopJournaler)
                .then(matchingHandler)
                .then(settlementHandler)
                .then(metricsReply);

        disruptor.start();
        log.info("✓ Disruptor pipeline started");

        log.info("Connecting to Binance WebSocket (BTC_USDT + ETH_USDT)...");
        BinanceDepthIngester ingester = new BinanceDepthIngester(
                disruptor.getRingBuffer(), metrics, cancelRatio);
        ingester.start();

        if (!ingester.awaitConnection(15_000)) {
            log.error("✗ Failed to connect to Binance WebSocket within 15 seconds");
            log.error("  Check your network connection or try a VPN (Binance may be blocked)");
            disruptor.shutdown();
            System.exit(1);
        }

        log.info("✓ Connected and ingesting live data");
        log.info("");
        log.info("═══════════════════ BENCHMARK RUNNING ═══════════════════");
        log.info("Duration: {} minutes | Press Ctrl+C to stop early", durationMinutes);
        log.info("=========================================================");
        log.info("");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "metrics-liquidity-pool");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(metrics::logPeriodicSnapshot, 10, 10, TimeUnit.SECONDS);

        // Flaw 5: Background liquidity provider thread to keep risk stable without hitting 0% or 100% reject rates
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (long id : verifiedAccountIds) {
                    wallets.deposit(id, "USDT", USDT_BALANCE);
                    wallets.deposit(id, "BTC", BTC_BALANCE);
                    wallets.deposit(id, "ETH", ETH_BALANCE);
                }
            } catch (Exception ignored) {
            }
        }, 10, 10, TimeUnit.SECONDS);

        long durationMs = durationMinutes * 60 * 1000;
        long startTime = System.currentTimeMillis();

        final String finalMode = mode;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("");
            log.info("Shutting down...");
            ingester.stop();
            scheduler.shutdown();

            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }

            long elapsed = System.currentTimeMillis() - startTime;
            metrics.printFinalReport(finalMode, elapsed, ingester.getDroppedEvents(), ingester.getParseErrors());
            disruptor.shutdown();
        }, "shutdown-hook"));

        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            log.info("Benchmark interrupted");
        }

        log.info("");
        log.info("Benchmark duration complete. Shutting down...");

        ingester.stop();
        scheduler.shutdown();

        Thread.sleep(3000);

        long elapsed = System.currentTimeMillis() - startTime;
        metrics.printFinalReport(mode, elapsed, ingester.getDroppedEvents(), ingester.getParseErrors());
        disruptor.shutdown();

        log.info("Benchmark complete. Exiting.");

        System.exit(0);
    }
}
