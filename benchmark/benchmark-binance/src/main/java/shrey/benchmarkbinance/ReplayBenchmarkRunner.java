package shrey.benchmarkbinance;

import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shrey.bank.proto.TradingProto;
import shrey.exchange.*;
import shrey.exchange.domain.*;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replays captured Binance depth data at maximum speed through the matching engine pipeline.
 * Duration-based (default 12 hours). Data is looped continuously until time expires.
 *
 * Modes:
 *   "replay"       — Matching engine only (Risk → NoopJournal → Match → Settle → Metrics)
 *   "replay-kafka" — Full pipeline including Kafka (Risk → Kafka → Match → Settle → Metrics)
 *
 * Checkpoints saved every 10 minutes. Ctrl+C saves final checkpoint before exit.
 */
public class ReplayBenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayBenchmarkRunner.class);

    private static final int RING_BUFFER_SIZE = 1 << 16;
    private static final int NUM_ACCOUNTS = 100;
    private static final long USDT_BALANCE = 1_000_000L;
    private static final long BTC_BALANCE  = 1_000_000L;
    private static final long ETH_BALANCE  = 1_000_000L;
    private static final long MIN_QTY = 1L;
    private static final long MAX_QTY = 50L;

    // Cap order book to prevent OOM. 200K active orders is realistic for a major crypto pair.
    private static final int MAX_BOOK_ORDERS = 200_000;

    // 5s warmup — at ~450K ops/sec that's ~2.25M events, enough for JIT C2
    private static final long REPLAY_WARMUP_NANOS = 5_000_000_000L;

    // Default: 12 hours
    private static final int DEFAULT_DURATION_MINUTES = 720;

    public static void main(String[] args) throws Exception {
        String dataFile = null;
        boolean useKafka = false;
        int durationMinutes = DEFAULT_DURATION_MINUTES;

        for (int i = 0; i < args.length; i++) {
            if ("--kafka".equals(args[i])) {
                useKafka = true;
            } else if ("--duration".equals(args[i]) && i + 1 < args.length) {
                durationMinutes = Integer.parseInt(args[i + 1]);
                i++;
            } else if (!args[i].startsWith("--")) {
                dataFile = args[i];
            }
        }

        if (dataFile == null) {
            File dir = new File(".");
            File[] candidates = dir.listFiles((d, name) -> name.startsWith("binance_depth_") && name.endsWith(".jsonl"));
            if (candidates != null && candidates.length > 0) {
                Arrays.sort(candidates, Comparator.comparingLong(File::lastModified).reversed());
                dataFile = candidates[0].getName();
                log.info("Auto-detected data file: {}", dataFile);
            } else {
                System.err.println("No data file found. Run: ./gradlew :benchmark:benchmark-binance:run-capture");
                System.exit(1);
            }
        }

        String mode = useKafka ? "replay-kafka" : "replay";

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║     BINANCE REPLAY → LMAX DISRUPTOR BENCHMARK              ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        log.info("Mode:        {}", mode.toUpperCase());
        log.info("Data file:   {}", dataFile);
        log.info("Duration:    {} minutes ({} hours)", durationMinutes, String.format("%.1f", durationMinutes / 60.0));
        log.info("Pipeline:    Risk → {} → Match → Settle → Metrics",
            useKafka ? "Kafka Journal" : "Noop Journal");
        log.info("Warmup:      5s | Book cap: {} orders", MAX_BOOK_ORDERS);
        log.info("JVM:         {} {}", System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
        log.info("Heap:        {}MB max", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        log.info("Processors:  {}", Runtime.getRuntime().availableProcessors());
        log.info("");

        // Load data
        log.info("Loading data from {}...", dataFile);
        List<OrderEvent> events = loadEvents(dataFile);
        log.info("✓ Loaded {} order events", events.size());
        if (events.isEmpty()) { log.error("✗ No events. Run BinanceDataCapture first."); System.exit(1); }

        events.sort(Comparator.comparingLong(e -> e.timestamp));
        long timeSpanMs = events.get(events.size() - 1).timestamp - events.get(0).timestamp;
        log.info("  Time span: {}s | BUY: {} | SELL: {} | CANCEL: {}",
            timeSpanMs / 1000,
            events.stream().filter(e -> e.isBuy).count(),
            events.stream().filter(e -> !e.isBuy).count(),
            events.stream().filter(e -> e.isCancel).count());
        log.info("");

        runReplay(events, durationMinutes, mode, useKafka);
    }

    private static void runReplay(List<OrderEvent> events, int durationMinutes,
                                   String mode, boolean useKafka) throws Exception {

        // Domain setup
        TradingWallets wallets = new TradingWallets();
        CircuitBreaker circuitBreaker = new CircuitBreaker();
        RiskEngine riskEngine = new RiskEngine(circuitBreaker);
        OrderBookManager orderBookManager = new OrderBookManager();
        LatencyTracker latencyTracker = new LatencyTracker();
        SimulationMetrics metrics = new SimulationMetrics(orderBookManager, REPLAY_WARMUP_NANOS);

        // Pre-seed accounts — capture IDs for verified top-up
        final long[] verifiedAccountIds = new long[NUM_ACCOUNTS];
        for (int i = 0; i < NUM_ACCOUNTS; i++) {
            long id = wallets.newTradingAccount();
            verifiedAccountIds[i] = id;
            wallets.deposit(id, "USDT", USDT_BALANCE);
            wallets.deposit(id, "BTC", BTC_BALANCE);
            wallets.deposit(id, "ETH", ETH_BALANCE);
        }

        // Pipeline handlers
        RiskValidationHandler riskHandler = new RiskValidationHandler(wallets, riskEngine);
        MatchingHandler matchingHandler = new MatchingHandler(orderBookManager);
        SettlementHandler settlementHandler = new SettlementHandler(wallets, riskEngine, circuitBreaker, latencyTracker);
        MetricsReplyHandler metricsReply = new MetricsReplyHandler(metrics);

        // Journaler
        com.lmax.disruptor.EventHandler<CommandBufferEvent> journaler =
            useKafka ? createKafkaJournaler() : new NoopJournaler();

        // Disruptor
        Disruptor<CommandBufferEvent> disruptor = new Disruptor<>(
            CommandBufferEvent::new, RING_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new YieldingWaitStrategy());

        disruptor.handleEventsWith(riskHandler)
                 .then(journaler)
                 .then(matchingHandler)
                 .then(settlementHandler)
                 .then(metricsReply);

        disruptor.start();
        log.info("✓ Disruptor pipeline started");

        RingBuffer<CommandBufferEvent> ringBuffer = disruptor.getRingBuffer();

        // Scheduled metrics logging
        int snapshotSec = durationMinutes >= 60 ? 30 : 10;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-logger"); t.setDaemon(true); return t;
        });
        scheduler.scheduleAtFixedRate(metrics::logPeriodicSnapshot, snapshotSec, snapshotSec, TimeUnit.SECONDS);

        // Background liquidity provider — uses verified IDs from newTradingAccount()
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (long id : verifiedAccountIds) {
                    wallets.deposit(id, "USDT", USDT_BALANCE);
                    wallets.deposit(id, "BTC", BTC_BALANCE);
                    wallets.deposit(id, "ETH", ETH_BALANCE);
                }
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.SECONDS);

        // Checkpoint scheduler — every 10 minutes
        ScheduledExecutorService checkpointScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "checkpoint-saver"); t.setDaemon(true); return t;
        });

        // Counters
        AtomicLong orderIdCounter = new AtomicLong(0);
        AtomicLong publishedCount = new AtomicLong(0);
        AtomicLong droppedCount = new AtomicLong(0);
        AtomicLong passCount = new AtomicLong(0);
        AtomicBoolean shutdownComplete = new AtomicBoolean(false);

        final int CANCEL_BUF = 16384;
        final int CANCEL_MASK = CANCEL_BUF - 1;
        long[] recentOrderIds = new long[CANCEL_BUF];
        long[] recentAccountIds = new long[CANCEL_BUF];
        int[] recentSymbols = new int[CANCEL_BUF];
        // long to prevent overflow — at 407K ops/sec, int overflows at ~87 minutes
        long cancelWriteIdx = 0;
        long cancelReadIdx = 0;

        // Pre-convert for cache-friendly iteration
        OrderEvent[] eventArray = events.toArray(new OrderEvent[0]);
        int eventCount = eventArray.length;

        long replayStartNanos = System.nanoTime();
        long deadlineNanos = replayStartNanos + (durationMinutes * 60L * 1_000_000_000L);

        // Schedule checkpoints
        checkpointScheduler.scheduleAtFixedRate(() -> {
            saveCheckpoint(mode, useKafka, replayStartNanos, publishedCount.get(),
                passCount.get(), 0, eventCount, metrics);
        }, 10, 10, TimeUnit.MINUTES);

        // Shutdown hook for Ctrl+C
        final long startRef = replayStartNanos;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (shutdownComplete.getAndSet(true)) return;
            double elapsed = (System.nanoTime() - startRef) / 1_000_000_000.0;
            log.info("");
            log.info("Interrupted after {}s — {} events, {} passes, {} ops/sec",
                String.format("%.1f", elapsed),
                String.format("%,d", publishedCount.get()),
                passCount.get(),
                String.format("%,.0f", publishedCount.get() / Math.max(elapsed, 0.001)));
            saveCheckpoint(mode, useKafka, startRef, publishedCount.get(),
                passCount.get(), 0, eventCount, metrics);
            try {
                metrics.endBurst();
                scheduler.shutdown();
                checkpointScheduler.shutdown();
                metrics.printFinalReport(mode, (long)(elapsed * 1000), droppedCount.get(), 0);
                disruptor.shutdown();
            } catch (Exception e) {
                log.warn("Shutdown error: {}", e.getMessage());
            }
        }, "shutdown-hook"));

        // ===== START REPLAY =====
        log.info("");
        log.info("═══════════════════ REPLAY STARTING ═══════════════════");
        log.info("  Looping {} events continuously for {} hours",
            eventCount, String.format("%.1f", durationMinutes / 60.0));
        log.info("  Ctrl+C to stop early — checkpoint saves every 10min");
        log.info("=======================================================");
        log.info("");

        metrics.startBurst();

        // ===== Main replay loop =====
        while (System.nanoTime() < deadlineNanos) {
            passCount.incrementAndGet();
            long pass = passCount.get();

            if (pass % 100 == 0) {
                double elapsed = (System.nanoTime() - replayStartNanos) / 1_000_000_000.0;
                double throughput = publishedCount.get() / Math.max(elapsed, 0.001);
                double hoursRemaining = (deadlineNanos - System.nanoTime()) / 1_000_000_000.0 / 3600.0;
                log.info("  Pass {} | {} events | {} ops/sec | {}h remaining",
                    pass,
                    String.format("%,d", publishedCount.get()),
                    String.format("%,.0f", throughput),
                    String.format("%.1f", hoursRemaining));
            }

            for (int i = 0; i < eventCount; i++) {
                OrderEvent event = eventArray[i];
                long orderId = orderIdCounter.incrementAndGet();
                long accountId = (orderId % NUM_ACCOUNTS) + 1;

                TradingProto.TradingCommandLog commandLog;

                if (event.isCancel) {
                    if (cancelReadIdx >= cancelWriteIdx) continue;
                    int cancelIdx = (int)(cancelReadIdx & CANCEL_MASK);
                    cancelReadIdx++;
                    
                    long cancelOrderId = recentOrderIds[cancelIdx];
                    if (cancelOrderId == 0) continue;
                    String cancelSymbol = recentSymbols[cancelIdx] == 0 ? "BTC_USDT" : "ETH_USDT";
                    commandLog = TradingProto.TradingCommandLog.newBuilder()
                        .setCancelOrderCommand(TradingProto.CancelOrderCommand.newBuilder()
                            .setAccountId(recentAccountIds[cancelIdx])
                            .setOrderId(cancelOrderId)
                            .setSymbol(cancelSymbol)
                            .setTimestampNanos(System.nanoTime())
                            .build())
                        .build();
                    metrics.recordCancel();
                } else {
                    long price = parsePrice(event.price);
                    long quantity = parseQuantity(event.qty);
                    if (price <= 0 || quantity <= 0) continue;
                    TradingProto.OrderSide side = event.isBuy ?
                        TradingProto.OrderSide.BUY : TradingProto.OrderSide.SELL;
                    commandLog = TradingProto.TradingCommandLog.newBuilder()
                        .setPlaceOrderCommand(TradingProto.PlaceOrderCommand.newBuilder()
                            .setAccountId(accountId)
                            .setSymbol(event.symbol)
                            .setSide(side)
                            .setOrderType(TradingProto.OrderType.LIMIT)
                            .setPrice(price)
                            .setQuantity(quantity)
                            .setTimeInForce(TradingProto.TimeInForce.GTC)
                            .setTimestampNanos(System.nanoTime())
                            .build())
                        .build();
                    int idx = (int)(cancelWriteIdx & CANCEL_MASK);
                    recentOrderIds[idx] = orderId;
                    recentAccountIds[idx] = accountId;
                    recentSymbols[idx] = event.symbol.equals("BTC_USDT") ? 0 : 1;
                    cancelWriteIdx++;
                    metrics.recordPlace();
                }

                // Publish to ring buffer by blocking for space (measuring max throughput capability)
                long sequence = ringBuffer.next();
                try {
                    CommandBufferEvent buf = ringBuffer.get(sequence);
                    buf.setCommand(new BaseCommand(commandLog));
                    buf.setEntryTimestampNanos(System.nanoTime());
                    buf.setRejected(false);
                    buf.setOrder(null);
                    buf.setMatchingResult(null);
                    buf.setResult(null);
                    buf.setRiskFinishedNanos(0);
                    buf.setMatchFinishedNanos(0);
                } finally {
                    ringBuffer.publish(sequence);
                }
                publishedCount.incrementAndGet();

                // Check deadline every 16K events
                if ((i & 0x3FFF) == 0 && System.nanoTime() >= deadlineNanos) {
                    break;
                }
            }

            // Prune order books after each pass to prevent OOM
            // Drain ring buffer first so matching thread is idle
            try {
                long drainDeadline = System.nanoTime() + 100_000_000L; // 100ms timeout
                while (ringBuffer.getMinimumGatingSequence() < ringBuffer.getCursor()
                       && System.nanoTime() < drainDeadline) {
                    Thread.onSpinWait();
                }
                for (String sym : new String[]{"BTC_USDT", "ETH_USDT", "SOL_USDT"}) {
                    OrderBook book = orderBookManager.getOrderBook(sym);
                    int before = book.getActiveOrderCount();
                    if (before > MAX_BOOK_ORDERS) {
                        int pruned = book.pruneOldestOrders(MAX_BOOK_ORDERS);
                        if (pass % 500 == 0) {
                            log.info("  Pruned {} {} orders ({} → {})",
                                pruned, sym, before, book.getActiveOrderCount());
                        }
                    }
                }
            } catch (Exception e) {
                // Concurrent modification — safe to ignore, will retry next pass
            }
        }

        // ===== DRAIN AND REPORT =====
        long replayEndNanos = System.nanoTime();
        double replayDurationSec = (replayEndNanos - replayStartNanos) / 1_000_000_000.0;

        log.info("");
        log.info("Duration complete. Draining pipeline...");

        long drainStart = System.currentTimeMillis();
        while (ringBuffer.getMinimumGatingSequence() < ringBuffer.getCursor()
               && System.currentTimeMillis() - drainStart < 30_000) {
            Thread.sleep(100);
        }
        Thread.sleep(2000);

        metrics.endBurst();

        long totalFinishNanos = System.nanoTime();
        double totalDurationSec = (totalFinishNanos - replayStartNanos) / 1_000_000_000.0;
        double replayThroughput = publishedCount.get() / replayDurationSec;
        double totalThroughput = publishedCount.get() / totalDurationSec;

        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║         BINANCE REPLAY BENCHMARK — FINAL RESULTS               ║");
        log.info("╚══════════════════════════════════════════════════════════════════╝");
        log.info("");
        log.info("  Mode:           {}", mode.toUpperCase());
        log.info("  Pipeline:       Risk → {} → Match → Settle → Metrics",
            useKafka ? "Kafka Journal" : "Noop Journal");
        log.info("  Data:           {} events (looped {} times)", eventCount, passCount.get());
        log.info("  Duration:       {}h {}m", (int)(totalDurationSec / 3600), (int)((totalDurationSec % 3600) / 60));
        log.info("");
        log.info("  ┌─── THROUGHPUT ───────────────────┐");
        log.info("  │  Total events:      {}",  String.format("%,d", publishedCount.get()));
        log.info("  │  Replay throughput: {} ops/sec", String.format("%,.0f", replayThroughput));
        log.info("  │  E2E throughput:    {} ops/sec", String.format("%,.0f", totalThroughput));
        log.info("  └──────────────────────────────────┘");

        scheduler.shutdown();
        checkpointScheduler.shutdown();
        saveCheckpoint(mode, useKafka, replayStartNanos, publishedCount.get(),
            passCount.get(), droppedCount.get(), eventCount, metrics);
        metrics.printFinalReport(mode, (long)(totalDurationSec * 1000), droppedCount.get(), 0);
        disruptor.shutdown();
        shutdownComplete.set(true);

        log.info("Replay benchmark complete after {} passes.", passCount.get());
    }

    // ===== Checkpoint =====

    private static void saveCheckpoint(String mode, boolean useKafka, long startNanos,
                                        long published, long passes, long dropped,
                                        int eventCount, SimulationMetrics metrics) {
        try {
            double elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            double throughput = elapsedSec > 0 ? published / elapsedSec : 0;

            String cp = String.format("{\n" +
                "  \"mode\": \"%s\",\n" +
                "  \"pipeline\": \"%s\",\n" +
                "  \"dataSource\": \"Real Binance depth events (captured offline, looped)\",\n" +
                "  \"checkpointTime\": \"%s\",\n" +
                "  \"elapsedSeconds\": %d,\n" +
                "  \"elapsedHours\": \"%s\",\n" +
                "  \"totalEvents\": %d,\n" +
                "  \"totalPasses\": %d,\n" +
                "  \"sourceEvents\": %d,\n" +
                "  \"throughputOpsPerSec\": %d,\n" +
                "  \"droppedEvents\": %d\n" +
                "}", mode, useKafka ? "Risk→Kafka→Match→Settle" : "Risk→NoopJournal→Match→Settle",
                 LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                 Math.round(elapsedSec), String.format("%.2f", elapsedSec / 3600.0),
                 published, passes, eventCount, Math.round(throughput), dropped);

            // Latest results (overwritten each time)
            try (FileWriter fw = new FileWriter("benchmark_results_" + mode + ".json")) {
                fw.write(cp);
            }

            // Timestamped checkpoint (history)
            File dir = new File("checkpoints");
            if (!dir.exists()) dir.mkdirs();
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            String cpFile = "checkpoints/checkpoint_" + ts + ".json";
            try (FileWriter fw = new FileWriter(cpFile)) {
                fw.write(cp);
            }

            log.info("✓ Checkpoint saved: {} | {} events | {} ops/sec | {}h elapsed",
                cpFile,
                String.format("%,d", published),
                String.format("%,.0f", throughput),
                String.format("%.1f", elapsedSec / 3600.0));
        } catch (Exception e) {
            log.warn("Checkpoint save failed: {}", e.getMessage());
        }
    }

    // ===== Kafka =====

    private static CommandBufferJournaler createKafkaJournaler() {
        try {
            var props = new java.util.Properties();
            props.put("bootstrap.servers", "localhost:9092");
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
            props.put("acks", "1");
            props.put("batch.size", "65536");
            props.put("linger.ms", "1");
            props.put("buffer.memory", "67108864");
            var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(props);
            var kafkaProps = new CommandLogKafkaProperties();
            kafkaProps.setTopic("exchange-commands-benchmark");
            kafkaProps.setGroupId("benchmark-replay");
            var leaderProps = new shrey.exchange.cluster.LeaderProperties();
            leaderProps.setLogsChunkSize(200);
            log.info("✓ Kafka journaler connected (localhost:9092)");
            return new CommandBufferJournalerImpl(producer, kafkaProps, leaderProps);
        } catch (Exception e) {
            log.error("✗ Kafka failed: {}. Use --replay mode without Kafka.", e.getMessage());
            System.exit(1);
            return null;
        }
    }

    // ===== Data loading =====

    private static List<OrderEvent> loadEvents(String filename) throws IOException {
        List<OrderEvent> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                OrderEvent event = parseEventLine(line);
                if (event != null) events.add(event);
            }
        }
        return events;
    }

    private static OrderEvent parseEventLine(String line) {
        OrderEvent e = new OrderEvent();
        int tsIdx = line.indexOf("\"ts\":");
        if (tsIdx >= 0) {
            int tsEnd = line.indexOf(',', tsIdx + 5);
            if (tsEnd > tsIdx + 5) {
                try { e.timestamp = Long.parseLong(line.substring(tsIdx + 5, tsEnd)); }
                catch (NumberFormatException ignored) {}
            }
        }
        int sideIdx = line.indexOf("\"side\":\"");
        if (sideIdx >= 0) e.isBuy = line.charAt(sideIdx + 8) == 'B';
        int priceIdx = line.indexOf("\"price\":\"");
        if (priceIdx >= 0) {
            int priceEnd = line.indexOf('"', priceIdx + 9);
            if (priceEnd > priceIdx + 9) e.price = line.substring(priceIdx + 9, priceEnd);
        }
        int qtyIdx = line.indexOf("\"qty\":\"");
        if (qtyIdx >= 0) {
            int qtyEnd = line.indexOf('"', qtyIdx + 7);
            if (qtyEnd > qtyIdx + 7) e.qty = line.substring(qtyIdx + 7, qtyEnd);
        }
        int symIdx = line.indexOf("\"symbol\":\"");
        if (symIdx >= 0) {
            int symEnd = line.indexOf('"', symIdx + 10);
            if (symEnd > symIdx + 10) e.symbol = line.substring(symIdx + 10, symEnd);
        }
        int typeIdx = line.indexOf("\"type\":\"");
        if (typeIdx >= 0) e.isCancel = line.charAt(typeIdx + 8) == 'C';
        if (e.price == null || e.symbol == null) return null;
        return e;
    }

    private static long parsePrice(String priceStr) {
        try {
            int dot = priceStr.indexOf('.');
            return dot > 0 ? Long.parseLong(priceStr.substring(0, dot)) : Long.parseLong(priceStr);
        } catch (NumberFormatException ex) { return 0; }
    }

    private static long parseQuantity(String qtyStr) {
        try {
            double qty = Double.parseDouble(qtyStr);
            return qty <= 0.0 ? 0 : Math.max(MIN_QTY, Math.min(MAX_QTY, (long)(qty * 10)));
        } catch (NumberFormatException ex) { return 0; }
    }

    static class OrderEvent {
        long timestamp;
        boolean isBuy;
        String price;
        String qty;
        String symbol;
        boolean isCancel;
    }
}
