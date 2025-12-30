package shrey.benchmarkbinance;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures raw Binance WebSocket depth updates to a JSONL file for offline
 * replay.
 *
 * Each line in the output file is a raw depth event:
 * {"ts":1712345678,"side":"BUY","price":67523.45,"qty":0.123,"symbol":"BTC_USDT","type":"LIMIT"}
 * {"ts":1712345678,"side":"SELL","price":67524.00,"qty":0.0,"symbol":"BTC_USDT","type":"CANCEL"}
 *
 * Usage:
 * ./gradlew :benchmark:benchmark-binance:run-capture
 * ./gradlew :benchmark:benchmark-binance:run-capture --args="--duration 30" (30
 * minutes)
 *
 * Output: binance_depth_<duration>min.jsonl
 */
public class BinanceDataCapture {

    private static final Logger log = LoggerFactory.getLogger(BinanceDataCapture.class);

    private static final String BINANCE_WS_URL = "wss://stream.binance.com:9443/stream?streams=btcusdt@depth@100ms/ethusdt@depth@100ms";

    public static void main(String[] args) throws Exception {
        int durationMinutes = 30; // default
        for (int i = 0; i < args.length; i++) {
            if ("--duration".equals(args[i]) && i + 1 < args.length) {
                durationMinutes = Integer.parseInt(args[i + 1]);
            }
        }

        String outputFile = "binance_depth_" + durationMinutes + "min.jsonl";

        log.info("        BINANCE DEPTH DATA CAPTURE                      ");
        log.info("Duration:    {} minutes", durationMinutes);
        log.info("Output:      {}", outputFile);
        log.info("Symbols:     BTC_USDT + ETH_USDT (depth@100ms)");
        log.info("");

        AtomicLong eventCount = new AtomicLong(0);
        AtomicLong messageCount = new AtomicLong(0);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile), 1 << 16)) {

            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder().url(BINANCE_WS_URL).build();
            final long startTime = System.currentTimeMillis();
            final long endTime = startTime + (durationMinutes * 60L * 1000L);

            WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    log.info("✓ Connected to Binance WebSocket");
                    connected.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    if (System.currentTimeMillis() >= endTime) {
                        webSocket.close(1000, "Capture complete");
                        done.countDown();
                        return;
                    }
                    try {
                        long events = parseAndWrite(text, writer);
                        eventCount.addAndGet(events);
                        long msgs = messageCount.incrementAndGet();
                        if (msgs % 500 == 0) {
                            log.info("  Captured {} WS messages → {} order events ({}s elapsed)",
                                    msgs, eventCount.get(),
                                    (System.currentTimeMillis() - startTime) / 1000);
                        }
                    } catch (IOException e) {
                        log.error("Write error: {}", e.getMessage());
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("WebSocket failure: {}", t.getMessage());
                    done.countDown();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    log.info("WebSocket closed: {}", reason);
                    done.countDown();
                }
            });

            if (!connected.await(15, TimeUnit.SECONDS)) {
                log.error("✗ Failed to connect. Check network/VPN.");
                System.exit(1);
            }

            log.info("Capturing for {} minutes... (Ctrl+C to stop early)", durationMinutes);

            done.await(durationMinutes + 1, TimeUnit.MINUTES);
            ws.close(1000, "Done");
            client.dispatcher().executorService().shutdown();

            writer.flush();
        }

        log.info("");
        log.info("═══════════════════ CAPTURE COMPLETE ═══════════════════");
        log.info("  WebSocket messages:  {}", messageCount.get());
        log.info("  Order events:        {}", eventCount.get());
        log.info("  File:                {}", outputFile);
        log.info("  Duration:            {} minutes", durationMinutes);
        log.info("");
        log.info("  Next: run replay benchmark:");
        log.info("    ./gradlew :benchmark:benchmark-binance:run-replay --args=\"{}\"", outputFile);
        log.info("========================================================");
    }

    /**
     * Parse a Binance combined stream message and write individual order events as
     * JSONL.
     * Zero-dependency manual parsing (no org.json) to keep it fast.
     *
     * Returns number of events written.
     */
    private static long parseAndWrite(String text, BufferedWriter writer) throws IOException {
        // Extract stream name
        int streamIdx = text.indexOf("\"stream\":\"");
        if (streamIdx < 0)
            return 0;
        streamIdx += 10;
        char firstChar = text.charAt(streamIdx);
        String symbol;
        if (firstChar == 'b')
            symbol = "BTC_USDT";
        else if (firstChar == 'e')
            symbol = "ETH_USDT";
        else
            return 0;

        // Extract event timestamp "E":timestamp
        long timestamp = 0;
        int tsIdx = text.indexOf("\"E\":");
        if (tsIdx >= 0) {
            int tsEnd = text.indexOf(',', tsIdx + 4);
            if (tsEnd > tsIdx + 4) {
                try {
                    timestamp = Long.parseLong(text.substring(tsIdx + 4, tsEnd));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        long count = 0;

        // Parse bids
        int bidsIdx = text.indexOf("\"b\":[");
        if (bidsIdx >= 0) {
            count += writeSide(text, bidsIdx + 4, writer, symbol, "BUY", timestamp);
        }

        // Parse asks
        int asksIdx = text.indexOf("\"a\":[");
        if (asksIdx >= 0) {
            count += writeSide(text, asksIdx + 4, writer, symbol, "SELL", timestamp);
        }

        return count;
    }

    private static long writeSide(String text, int from, BufferedWriter writer,
            String symbol, String side, long timestamp) throws IOException {
        int end = findMatchingBracket(text, from);
        if (end <= from + 1)
            return 0;

        long count = 0;
        int pos = from + 1;

        while (pos < end) {
            int levelStart = text.indexOf('[', pos);
            if (levelStart < 0 || levelStart >= end)
                break;
            int levelEnd = text.indexOf(']', levelStart);
            if (levelEnd < 0 || levelEnd > end)
                break;

            // Extract price and qty
            int q1 = text.indexOf('"', levelStart + 1);
            int q2 = text.indexOf('"', q1 + 1);
            int q3 = text.indexOf('"', q2 + 1);
            int q4 = text.indexOf('"', q3 + 1);

            if (q1 >= 0 && q2 >= 0 && q3 >= 0 && q4 >= 0 && q4 <= levelEnd) {
                String priceStr = text.substring(q1 + 1, q2);
                String qtyStr = text.substring(q3 + 1, q4);

                double qty = 0;
                try {
                    qty = Double.parseDouble(qtyStr);
                } catch (NumberFormatException ignored) {
                }

                String type = qty == 0.0 ? "CANCEL" : "LIMIT";

                // Write JSONL line — compact, no library needed
                writer.write("{\"ts\":");
                writer.write(Long.toString(timestamp));
                writer.write(",\"side\":\"");
                writer.write(side);
                writer.write("\",\"price\":\"");
                writer.write(priceStr);
                writer.write("\",\"qty\":\"");
                writer.write(qtyStr);
                writer.write("\",\"symbol\":\"");
                writer.write(symbol);
                writer.write("\",\"type\":\"");
                writer.write(type);
                writer.write("\"}");
                writer.newLine();
                count++;
            }

            pos = levelEnd + 1;
        }

        return count;
    }

    private static int findMatchingBracket(String text, int start) {
        int depth = 1;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[')
                depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return text.length();
    }
}
