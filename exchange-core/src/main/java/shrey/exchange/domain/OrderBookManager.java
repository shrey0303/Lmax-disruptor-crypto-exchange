package shrey.exchange.domain;

import java.util.HashMap;
import java.util.Map;

public class OrderBookManager {

    private final Map<String, OrderBook> orderBooks = new HashMap<>();

    public OrderBook getOrderBook(String symbol) {
        return orderBooks.computeIfAbsent(symbol, OrderBook::new);
    }

    public MatchingResult processOrder(Order order) {
        return getOrderBook(order.getSymbol()).processOrder(order);
    }

    public Order cancelOrder(String symbol, long orderId, long accountId) {
        return getOrderBook(symbol).cancelOrder(orderId, accountId);
    }
}
