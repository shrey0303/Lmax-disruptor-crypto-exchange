package shrey.exchange.domain;

import lombok.RequiredArgsConstructor;
import shrey.bank.proto.TradingProto.OrderSide;
import shrey.common.exception.Exchange4xxException;

@RequiredArgsConstructor
public class RiskEngine {

    private final CircuitBreaker circuitBreaker;

    // Hardcoded for demonstration. In production this would be configurable per pair.
    private static final long MAX_ORDER_VALUE = 1_000_000_000_000L; // Max order value
    private static final long MAX_POSITION_LIMIT = 5_000_000_000_000L; // Max total balance hold

    public void validateOrderPlacement(Order order, TradingWallets wallets) {
        circuitBreaker.validateNotHalted(order.getSymbol(), order.getTimestampNanos());
        validateOrderSize(order);
        validateAccountBalanceAndPosition(order, wallets);
    }

    private void validateOrderSize(Order order) {
        if (order.getQuantity() <= 0) {
            throw new Exchange4xxException("Order quantity must be greater than 0");
        }
        if (order.getPrice() <= 0 && order.getType() == shrey.bank.proto.TradingProto.OrderType.LIMIT) {
             throw new Exchange4xxException("Limit order price must be greater than 0");
        }
        long estimatedValue = safeMultiply(order.getQuantity(), Math.max(order.getPrice(), 1));
        if (estimatedValue > MAX_ORDER_VALUE) {
             throw new Exchange4xxException("Order value exceeds maximum allowed limits");
        }
    }

    private void validateAccountBalanceAndPosition(Order order, TradingWallets wallets) {
        TradingWallet wallet = wallets.getWallet(order.getAccountId());
        if (wallet == null) {
            throw new Exchange4xxException("Trading account not found: " + order.getAccountId());
        }

        String symbol = order.getSymbol();
        int sep = symbol.indexOf('_');
        String baseAsset = symbol.substring(0, sep);
        String quoteAsset = symbol.substring(sep + 1);

        if (order.getSide() == OrderSide.BUY) {
            long requiredQuote = safeMultiply(order.getQuantity(), Math.max(order.getPrice(), 1));
            if (wallet.getAvailableBalance(quoteAsset) < requiredQuote) {
                throw new Exchange4xxException("Insufficient " + quoteAsset + " to place BUY order");
            }
            if (wallet.getHold(quoteAsset) + requiredQuote > MAX_POSITION_LIMIT) {
                throw new Exchange4xxException("Order would exceed maximum allowed position limits");
            }
        } else {
            long requiredBase = order.getQuantity();
            if (wallet.getAvailableBalance(baseAsset) < requiredBase) {
                throw new Exchange4xxException("Insufficient " + baseAsset + " to place SELL order");
            }
            if (wallet.getHold(baseAsset) + requiredBase > MAX_POSITION_LIMIT) {
                throw new Exchange4xxException("Order would exceed maximum allowed position limits");
            }
        }
    }

    public void holdAssetsForOrder(Order order, TradingWallets wallets) {
        String symbol = order.getSymbol();
        int sep = symbol.indexOf('_');
        String baseAsset = symbol.substring(0, sep);
        String quoteAsset = symbol.substring(sep + 1);

        if (order.getSide() == OrderSide.BUY) {
            long holdAmount = safeMultiply(order.getQuantity(), order.getPrice());
            wallets.hold(order.getAccountId(), quoteAsset, holdAmount);
        } else {
            long holdAmount = order.getQuantity();
            wallets.hold(order.getAccountId(), baseAsset, holdAmount);
        }
    }
    
    public void releaseAssetsForCancel(Order order, TradingWallets wallets) {
        String symbol = order.getSymbol();
        int sep = symbol.indexOf('_');
        String baseAsset = symbol.substring(0, sep);
        String quoteAsset = symbol.substring(sep + 1);

        if (order.getSide() == OrderSide.BUY) {
            long releaseAmount = safeMultiply(order.getRemainingQuantity(), order.getPrice());
            wallets.release(order.getAccountId(), quoteAsset, releaseAmount);
        } else {
            long releaseAmount = order.getRemainingQuantity();
            wallets.release(order.getAccountId(), baseAsset, releaseAmount);
        }
    }

    /**
     * Overflow-safe multiplication. Throws Exchange4xxException instead of
     * silently wrapping to a negative value that would bypass risk guards.
     */
    private static long safeMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new Exchange4xxException("Order value overflow: quantity * price exceeds system limits");
        }
    }
}

