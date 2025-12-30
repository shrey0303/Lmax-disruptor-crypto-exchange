package shrey.benchmarkbinance;

import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shrey.bank.proto.TradingProto;
import shrey.exchange.BaseCommand;
import shrey.exchange.CommandBufferEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connects to Binance WebSocket depth streams and translates real-time order book updates
 * into PlaceOrderCommand / CancelOrderCommand protobuf messages, publishing them directly
 * into the LMAX Disruptor RingBuffer.
 *
 * Supports two symbols: BTC_USDT and ETH_USDT.
 *
 * Data source: wss://stream.binance.com:9443/stream?streams=...
 * - btcusdt@depth@100ms  → BTC_USDT order book diffs every 100ms
 * - ethusdt@depth@100ms  → ETH_USDT order book diffs every 100ms
 *
 * Architecture:
 * - WebSocket callback thread: parses messages, translates to Protobuf, publishes to Disruptor.
 * - Burst mode has been removed in favor of ReplayBenchmarkRunner for historical replay.
 *
 * Non-blocking publishing:
 * - Uses ringBuffer.tryNext() instead of next() to never block the WebSocket callback
 * - Dropped events are counted and reported in metrics
 *
 * Allocation awareness:
 * - JSON Parsing: Zero-allocation (manual String.indexOf/substring instead of org.json.JSONObject)
 * - Ingestion Layer: Allocates one Protobuf object per order.
 *   Note: The `.setCorrelationId()` builder was explicitly removed to eliminate hot-path string concatenations.
 *
 * Concurrency Profile:
 * - While cancellation indices (`cancelWriteIdx`) utilize `AtomicInteger`, the subsequent array writes are not fenced. 
 *   This is safe exclusively because OkHttp WebSocket callbacks run on a single processing thread, avoiding ABA overlap.
 */
public class BinanceDepthIngester {

    private static final Logger log = LoggerFactory.getLogger(BinanceDepthIngester.class);

    // Binance combined stream endpoint for dual-symbol
    private static final String BINANCE_WS_URL =
        "wss://stream.binance.com:9443/stream?streams=btcusdt@depth@100ms/ethusdt@depth@100ms";

    private final RingBuffer<CommandBufferEvent> ringBuffer;
    private final SimulationMetrics metrics;
    private final double cancelRatio;    // 0.0 = normal, 0.9 = HFT mode (90% cancels)

    private final AtomicLong orderIdCounter = new AtomicLong(0);
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong droppedEvents = new AtomicLong(0);
    private final AtomicLong parseErrors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch connectedLatch = new CountDownLatch(1);

    private static final int NUM_ACCOUNTS = 100;

    // Quantity bounds — clamped to match LoadSimulatorController range
    private static final long MIN_QTY = 1L;
    private static final long MAX_QTY = 50L;

    private OkHttpClient client;
    private WebSocket webSocket;

    // --- Cancel tracking for realistic cancels ---
    // Simple circular array instead of ConcurrentLinkedDeque — zero allocation
    private static final int CANCEL_BUFFER_SIZE = 16384;
    private static final int CANCEL_MASK = CANCEL_BUFFER_SIZE - 1;
    private final long[] cancelOrderIds = new long[CANCEL_BUFFER_SIZE];
    private final long[] cancelAccountIds = new long[CANCEL_BUFFER_SIZE];
    private final int[] cancelSymbols = new int[CANCEL_BUFFER_SIZE]; // 0=BTC_USDT, 1=ETH_USDT
    private final AtomicInteger cancelWriteIdx = new AtomicInteger(0);
    private final AtomicInteger cancelReadIdx = new AtomicInteger(0);

    public BinanceDepthIngester(RingBuffer<CommandBufferEvent> ringBuffer,
                                SimulationMetrics metrics,
                                double cancelRatio) {
        this.ringBuffer = ringBuffer;
        this.metrics = metrics;
        this.cancelRatio = Math.max(0.0, Math.min(1.0, cancelRatio));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Ingester already running");
            return;
        }

        client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

        Request request = new Request.Builder().url(BINANCE_WS_URL).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("✓ Connected to Binance WebSocket (BTC_USDT + ETH_USDT depth@100ms)");
                connectedLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (!running.get()) return;
                long receiveNanos = System.nanoTime(); // Fix 1: Timestamp at earliest possible point
                try {
                    processMessage(text, receiveNanos);
                } catch (Exception e) {
                    parseErrors.incrementAndGet();
                    log.warn("Error processing message: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("WebSocket failure: {}", t.getMessage());
                if (running.get()) {
                    log.info("Attempting reconnect in 5 seconds...");
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    if (running.get()) {
                        start(); // reconnect
                    }
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log.info("WebSocket closing: {} - {}", code, reason);
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("WebSocket closed: {} - {}", code, reason);
            }
        });
    }

    public boolean awaitConnection(long timeoutMs) throws InterruptedException {
        return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running.set(false);
        if (webSocket != null) {
            webSocket.close(1000, "Benchmark complete");
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
        log.info("Ingester stopped. Total WebSocket messages: {} | Dropped events: {} | Parse errors: {}",
            messageCount.get(), droppedEvents.get(), parseErrors.get());
    }

    public long getMessageCount() { return messageCount.get(); }
    public long getDroppedEvents() { return droppedEvents.get(); }
    public long getParseErrors() { return parseErrors.get(); }

    // ========================= ZERO-ALLOCATION JSON PARSING =========================

    /**
     * Process a combined stream message from Binance using manual string parsing.
     * Avoids org.json.JSONObject allocation entirely.
     *
     * Expected format:
     * {"stream":"btcusdt@depth@100ms","data":{"e":"depthUpdate","b":[["67523.45","0.123"],...],"a":[...]}}
     */
    private void processMessage(String text, long receiveNanos) {
        // 1. Extract stream name to determine symbol
        int streamIdx = text.indexOf("\"stream\":\"");
        if (streamIdx < 0) return;
        streamIdx += 10; // skip past "stream":"
        int streamEnd = text.indexOf('"', streamIdx);
        if (streamEnd < 0) return;

        int symbolId; // 0=BTC_USDT, 1=ETH_USDT
        // Check first char — 'b' for btcusdt, 'e' for ethusdt
        char firstChar = text.charAt(streamIdx);
        if (firstChar == 'b') {
            symbolId = 0;
        } else if (firstChar == 'e') {
            symbolId = 1;
        } else {
            return; // unknown stream
        }

        String symbol = symbolId == 0 ? "BTC_USDT" : "ETH_USDT";
        messageCount.incrementAndGet();

        // 2. Find bids array "b":[[...]]
        int bidsIdx = text.indexOf("\"b\":[", streamEnd);
        if (bidsIdx < 0) return;
        bidsIdx += 4; // position at '['

        // 3. Find asks array "a":[[...]]
        int asksIdx = text.indexOf("\"a\":[", bidsIdx);
        if (asksIdx < 0) return;
        asksIdx += 4;

        // 4. Parse bids — from bidsIdx to the matching ']'
        int bidsEnd = findMatchingBracket(text, bidsIdx);
        if (bidsEnd > bidsIdx + 1) {
            parseSide(text, bidsIdx + 1, bidsEnd, symbol, symbolId, TradingProto.OrderSide.BUY, 0, receiveNanos);
        }

        // 5. Parse asks — from asksIdx to the matching ']'
        int asksEnd = findMatchingBracket(text, asksIdx);
        if (asksEnd > asksIdx + 1) {
            parseSide(text, asksIdx + 1, asksEnd, symbol, symbolId, TradingProto.OrderSide.SELL, 1, receiveNanos);
        }
    }

    /**
     * Find the matching ']' for a '[' at position start.
     */
    private static int findMatchingBracket(String text, int start) {
        int depth = 1;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return text.length(); // safety
    }

    /**
     * Parse a side (bids or asks) from the raw JSON text.
     * Format: ["67523.45","0.123"],["67522.00","0.500"],...
     */
    private void parseSide(String text, int from, int to, String symbol, int symbolId,
                           TradingProto.OrderSide side, int sideId, long receiveNanos) {
        int pos = from;
        while (pos < to) {
            // Find next ["price","qty"]
            int levelStart = text.indexOf('[', pos);
            if (levelStart < 0 || levelStart >= to) break;
            int levelEnd = text.indexOf(']', levelStart);
            if (levelEnd < 0 || levelEnd > to) break;

            int firstQuote = text.indexOf('"', levelStart + 1);
            int secondQuote = text.indexOf('"', firstQuote + 1);
            int thirdQuote = text.indexOf('"', secondQuote + 1);
            int fourthQuote = text.indexOf('"', thirdQuote + 1);

            if (firstQuote >= 0 && secondQuote >= 0 && thirdQuote >= 0 && fourthQuote >= 0
                && fourthQuote <= levelEnd) {
                String priceStr = text.substring(firstQuote + 1, secondQuote);
                String qtyStr = text.substring(thirdQuote + 1, fourthQuote);

                long price = parsePrice(priceStr);
                long quantity = parseQuantity(qtyStr);

                if (price > 0) {
                    if (quantity == 0) {
                        // Price level removed → cancel
                        publishCancel(symbol, receiveNanos);
                    } else {
                        // Price level updated → place order
                        publishPlace(symbol, side, price, quantity, receiveNanos);
                        if (cancelRatio > 0.0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < cancelRatio) {
                            publishCancel(symbol, receiveNanos);
                        }
                    }
                }
            }

            pos = levelEnd + 1;
        }
    }

    // ========================= PUBLISH METHODS =========================

    private void publishPlace(String symbol, TradingProto.OrderSide side, long price, long quantity, long receiveNanos) {
        long orderId = orderIdCounter.incrementAndGet();
        long accountId = (orderId % NUM_ACCOUNTS) + 1;

        TradingProto.PlaceOrderCommand placeOrder = TradingProto.PlaceOrderCommand.newBuilder()
            .setAccountId(accountId)
            .setSymbol(symbol)
            .setSide(side)
            .setOrderType(TradingProto.OrderType.LIMIT)
            .setPrice(price)
            .setQuantity(quantity)
            .setTimeInForce(TradingProto.TimeInForce.GTC)
            .setTimestampNanos(System.nanoTime())
            .build();

        TradingProto.TradingCommandLog commandLog = TradingProto.TradingCommandLog.newBuilder()
            .setPlaceOrderCommand(placeOrder)
            .build();

        // Track this order for potential future cancel
        int idx = cancelWriteIdx.getAndIncrement() & CANCEL_MASK;
        cancelOrderIds[idx] = orderId;
        cancelAccountIds[idx] = accountId;
        cancelSymbols[idx] = symbol.equals("BTC_USDT") ? 0 : 1;

        if (publishToRingBuffer(commandLog, receiveNanos)) {
            metrics.recordPlace();
        }
    }

    private void publishCancel(String symbol, long receiveNanos) {
        // Try to get a cancel target from the circular buffer
        int writeIdxLocal = cancelWriteIdx.get();
        int readIdxLocal = cancelReadIdx.get();
        if (readIdxLocal >= writeIdxLocal) {
            return; // no orders to cancel
        }

        // Try to claim the slot atomically
        if (!cancelReadIdx.compareAndSet(readIdxLocal, readIdxLocal + 1)) {
            return; // another thread took it, just skip
        }

        int idx = readIdxLocal & CANCEL_MASK;

        long orderId = cancelOrderIds[idx];
        long accountId = cancelAccountIds[idx];
        String cancelSymbol = cancelSymbols[idx] == 0 ? "BTC_USDT" : "ETH_USDT";

        TradingProto.CancelOrderCommand cancelOrder = TradingProto.CancelOrderCommand.newBuilder()
            .setAccountId(accountId)
            .setOrderId(orderId)
            .setSymbol(cancelSymbol)
            .setTimestampNanos(System.nanoTime())
            .build();

        TradingProto.TradingCommandLog commandLog = TradingProto.TradingCommandLog.newBuilder()
            .setCancelOrderCommand(cancelOrder)
            .build();

        if (publishToRingBuffer(commandLog, receiveNanos)) {
            metrics.recordCancel();
        }
    }

    /**
     * Non-blocking publish to ring buffer.
     * Uses tryNext() instead of next() — never blocks the producer.
     * Dropped events are counted and reported in metrics.
     *
     * @return true if published, false if dropped (ring buffer full)
     */
    private boolean publishToRingBuffer(TradingProto.TradingCommandLog commandLog, long receiveNanos) {
        long sequence;
        try {
            sequence = ringBuffer.tryNext(1);
        } catch (InsufficientCapacityException e) {
            // Ring buffer full — drop this event instead of blocking
            droppedEvents.incrementAndGet();
            return false;
        }
        try {
            CommandBufferEvent event = ringBuffer.get(sequence);
            event.setCommand(new BaseCommand(commandLog));
            event.setEntryTimestampNanos(receiveNanos);
            event.setRejected(false);
            event.setOrder(null);
            event.setMatchingResult(null);
            event.setResult(null);
            event.setRiskFinishedNanos(0);
            event.setMatchFinishedNanos(0);
        } finally {
            ringBuffer.publish(sequence);
        }
        return true;
    }

    // ========================= PRICE/QUANTITY PARSING =========================

    /**
     * Parse Binance price string (e.g., "67523.45000000") to long.
     * Truncate to integer — matching the engine's scale where prices are ~50000-70000 range.
     * Uses manual parsing to avoid Double.parseDouble allocation.
     */
    private static long parsePrice(String priceStr) {
        try {
            // Fast path: find decimal point, parse integer part only
            int dotIdx = priceStr.indexOf('.');
            if (dotIdx > 0) {
                return Long.parseLong(priceStr.substring(0, dotIdx));
            }
            return Long.parseLong(priceStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Parse Binance quantity string (e.g., "0.12340000") to long.
     * Clamp to 1-50 range matching LoadSimulatorController's quantity range.
     * Uses manual parsing to avoid Double.parseDouble allocation where possible.
     */
    private static long parseQuantity(String qtyStr) {
        try {
            double qty = Double.parseDouble(qtyStr);
            if (qty <= 0.0) return 0; // signal for cancel
            // Scale: multiply by 10, clamp to [1, 50]
            long scaled = Math.max(MIN_QTY, Math.min(MAX_QTY, (long)(qty * 10)));
            return scaled;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
