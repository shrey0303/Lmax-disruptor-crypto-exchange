package shrey.exchange.domain;

import shrey.common.exception.Exchange4xxException;
import java.util.HashMap;
import java.util.Map;

public class CircuitBreaker {
    
    private final Map<String, Long> lastPrices = new HashMap<>();
    private final Map<String, Long> referencePrices = new HashMap<>();
    private final Map<String, Long> haltedUntil = new HashMap<>();
    
    private static final double MAX_PRICE_MOVE_PERCENT = 0.10; // 10% move trips breaker
    private static final long HALT_DURATION_NANOS = 5L * 60L * 1_000_000_000L; // 5 minute halt

    public void updatePrice(String symbol, long newPrice, long timestampNanos) {
        Long refPrice = referencePrices.get(symbol);
        if (refPrice == null) {
            referencePrices.put(symbol, newPrice);
        } else {
            double move = Math.abs((double)(newPrice - refPrice) / refPrice);
            if (move > MAX_PRICE_MOVE_PERCENT) {
                haltedUntil.put(symbol, timestampNanos + HALT_DURATION_NANOS);
                referencePrices.put(symbol, newPrice);
            }
        }
        lastPrices.put(symbol, newPrice);
    }
    
    public void validateNotHalted(String symbol, long currentNanos) {
        Long haltEnd = haltedUntil.get(symbol);
        if (haltEnd != null) {
            if (currentNanos < haltEnd) {
                throw new Exchange4xxException("Trading is currently halted for " + symbol + " due to circuit breaker");
            } else {
                haltedUntil.remove(symbol);
            }
        }
    }
}
