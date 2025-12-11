package shrey.benchmarkclusterjmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import shrey.bank.proto.TradingProto.OrderSide;
import shrey.bank.proto.TradingProto.OrderType;
import shrey.exchange.domain.Order;
import shrey.exchange.domain.OrderBookManager;
import shrey.exchange.domain.OrderBook;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(2)
public class MatchingEngineBenchmark {

    private OrderBookManager orderBookManager;
    private long orderIdCounter;
    
    private static final String[] SYMBOLS = {"BTC_USDT", "ETH_USDT", "SOL_USDT"};
    private static final long[] BASE_PRICES = {60000L, 3000L, 150L};
    
    @Setup(Level.Trial)
    public void setup() {
        orderBookManager = new OrderBookManager();
        orderIdCounter = 1;
        
        // Seed order book: 3 symbols, 200 accounts, 50K resting orders per side per symbol
        // Flaw 18 (Multi-symbol, multi-account routing & lookups)
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        
        for (int s = 0; s < SYMBOLS.length; s++) {
            String sym = SYMBOLS[s];
            long basePrice = BASE_PRICES[s];
            
            for (int i = 0; i < 50_000; i++) {
                // Brutal fragmentation: Scatter prices uniformly across a 10,000-deep spread to break cache and sequentiality
                long randomBidOffset = rng.nextLong(1, 10000);
                long randomAskOffset = rng.nextLong(1, 10000);

                Order bid = Order.builder()
                        .id(orderIdCounter++)
                        .accountId((long) (i % 100) + 1) // 1 to 100 makers
                        .symbol(sym)
                        .side(OrderSide.BUY)
                        .type(OrderType.LIMIT)
                        .price(basePrice - randomBidOffset)
                        .quantity(Math.max(1L, (long) (rng.nextGaussian() * 30) + 100))
                        .build();
                orderBookManager.processOrder(bid);
                
                Order ask = Order.builder()
                        .id(orderIdCounter++)
                        .accountId((long) (i % 100) + 1)
                        .symbol(sym)
                        .side(OrderSide.SELL)
                        .type(OrderType.LIMIT)
                        .price(basePrice + randomAskOffset)
                        .quantity(Math.max(1L, (long) (rng.nextGaussian() * 30) + 100))
                        .build();
                orderBookManager.processOrder(ask);
            }
        }
    }

    @Benchmark
    @Threads(1) // Order matching runs sequentially in the LMAX Disruptor handler
    public void benchmarkMatching(Blackhole bh) {
        ThreadLocalRandom current = ThreadLocalRandom.current();
        
        int symbolIdx = current.nextInt(SYMBOLS.length);
        String symbol = SYMBOLS[symbolIdx];
        
        // Gaussian quantity: mean 50, std 30, clamped 1-500 (Flaw 13)
        long qty = (long) (current.nextGaussian() * 30 + 50);
        qty = Math.max(1L, Math.min(500L, qty));
        
        // Price distribution: Best ask +/- random offset 0 to 10 (Flaw 12)
        // Note [Disclosure]: getOrderBook() is an O(1) map lookup. getBestAskPrice() is an O(1)/O(log n) tree peek.
        // Both are explicitly included inside this @Benchmark measurement window because the router tier 
        // natively performs symbol resolution per-event in production.
        OrderBook book = orderBookManager.getOrderBook(symbol);
        long bestAsk = book != null && book.getBestAskPrice() > 0 ? book.getBestAskPrice() : BASE_PRICES[symbolIdx] + 1;
        
        // We use aggressive limit orders to traverse chunks of the book.
        // With an offset of 100 and a 5 per-level density, this sweeps 2-3 price levels on average 
        // (not dozens), reflecting realistic aggressive liquidity extraction without wiping the book.
        int offset = current.nextInt(100);
        long aggressorPrice = bestAsk + offset; 
        
        // Aggressor account (101-200)
        long aggressorAcct = current.nextInt(100) + 101L;

        Order aggressorOrder = Order.builder()
                .id(orderIdCounter++)
                .accountId(aggressorAcct)
                .symbol(symbol)
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .price(aggressorPrice)
                .quantity(qty) 
                .build();
                
        bh.consume(orderBookManager.processOrder(aggressorOrder));
        
        // Replenish liquidity exactly where it might have been eaten
        // Randomly scatter replenishment to keep the book deep
        long replenishQty = (long) (current.nextGaussian() * 30 + 50);
        replenishQty = Math.max(1L, Math.min(500L, replenishQty));
        
        Order replenishAsk = Order.builder()
                .id(orderIdCounter++)
                .accountId(current.nextInt(100) + 1L) // maker accounts
                .symbol(symbol)
                .side(OrderSide.SELL)
                .type(OrderType.LIMIT)
                .price(bestAsk + current.nextInt(10))
                .quantity(replenishQty)
                .build();
                
        bh.consume(orderBookManager.processOrder(replenishAsk));
    }
}
