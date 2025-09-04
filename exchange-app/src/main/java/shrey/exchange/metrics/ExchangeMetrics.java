package shrey.exchange.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import shrey.exchange.domain.LatencyStats;
import shrey.exchange.domain.LatencyTracker;
import shrey.exchange.domain.OrderBookManager;

import jakarta.annotation.PostConstruct;

@Component
@RequiredArgsConstructor
public class ExchangeMetrics {

    private final MeterRegistry registry;
    private final OrderBookManager orderBookManager;
    private final LatencyTracker latencyTracker;

    @PostConstruct
    public void init() {
        String symbol = "BTC_USDT";

        Gauge.builder("exchange.orderbook.best.bid", orderBookManager, 
                manager -> {
                    var book = manager.getOrderBook(symbol);
                    return book != null ? book.getBestBidPrice() : 0;
                })
             .tag("symbol", symbol)
             .description("Best bid price")
             .register(registry);

        Gauge.builder("exchange.orderbook.best.ask", orderBookManager, 
                manager -> {
                    var book = manager.getOrderBook(symbol);
                    return book != null ? book.getBestAskPrice() : 0;
                })
             .tag("symbol", symbol)
             .description("Best ask price")
             .register(registry);

        Gauge.builder("exchange.matching.latency.p50", latencyTracker,
                tracker -> tracker.getStats().getE2e().getP50())
             .description("P50 End-to-End Latency (us)")
             .register(registry);

        Gauge.builder("exchange.matching.latency.p99", latencyTracker,
                tracker -> tracker.getStats().getE2e().getP99())
             .description("P99 End-to-End Latency (us)")
             .register(registry);
    }
}
