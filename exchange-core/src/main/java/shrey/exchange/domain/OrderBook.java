package shrey.exchange.domain;

import lombok.Getter;
import org.agrona.collections.Long2ObjectHashMap;
import shrey.bank.proto.TradingProto.OrderSide;
import shrey.bank.proto.TradingProto.OrderStatus;
import shrey.bank.proto.TradingProto.OrderType;

import java.util.*;

public class OrderBook {

    public static class PriceLevel {
        public long price;
        public Order head;
        public Order tail;
        
        public boolean isEmpty() { return head == null; }
        
        public long getTotalQuantity() {
            long total = 0;
            Order current = head;
            while (current != null) {
                total += current.getRemainingQuantity();
                current = current.getNext();
            }
            return total;
        }
        
        public int getOrderCount() {
            int count = 0;
            Order current = head;
            while (current != null) {
                count++;
                current = current.getNext();
            }
            return count;
        }
        
        public void addLast(Order order) {
            order.setNext(null);
            if (head == null) {
                order.setPrev(null);
                head = tail = order;
            } else {
                tail.setNext(order);
                order.setPrev(tail);
                tail = order;
            }
        }
        
        public void remove(Order order) {
            if (order.getPrev() != null) {
                order.getPrev().setNext(order.getNext());
            } else {
                head = order.getNext();
            }
            
            if (order.getNext() != null) {
                order.getNext().setPrev(order.getPrev());
            } else {
                tail = order.getPrev();
            }
            order.setNext(null);
            order.setPrev(null);
        }
    }
    
    @Getter
    private final String symbol;

    @Getter
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder()); // Highest price first
    @Getter
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>(); // Lowest price first
    
    private final Long2ObjectHashMap<Order> activeOrders = new Long2ObjectHashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public long getBestBidPrice() {
        return bids.isEmpty() ? 0 : bids.firstKey();
    }

    public long getBestAskPrice() {
        return asks.isEmpty() ? 0 : asks.firstKey();
    }

    public MatchingResult processOrder(Order order) {
        MatchingResult result = MatchingResult.builder().order(order).build();
        
        if (order.getType() == OrderType.MARKET) {
            matchMarketOrder(order, result);
        } else if (order.getType() == OrderType.LIMIT) {
            matchLimitOrder(order, result);
        }
        
        return result;
    }

    private void matchMarketOrder(Order takerOrder, MatchingResult result) {
        TreeMap<Long, PriceLevel> orderBookSide = takerOrder.getSide() == OrderSide.BUY ? asks : bids;
        
        Iterator<Map.Entry<Long, PriceLevel>> iterator = orderBookSide.entrySet().iterator();
        
        while (iterator.hasNext() && takerOrder.getRemainingQuantity() > 0) {
            Map.Entry<Long, PriceLevel> entry = iterator.next();
            PriceLevel priceLevelQueue = entry.getValue();
            
            matchAgainstPriceLevel(takerOrder, priceLevelQueue, entry.getKey(), result);
            
            if (priceLevelQueue.isEmpty()) {
                iterator.remove();
            }
        }
        
        // Market orders that are not fully filled are cancelled immediately
        if (takerOrder.getRemainingQuantity() > 0) {
            takerOrder.setStatus(OrderStatus.CANCELLED);
        } else {
            takerOrder.setStatus(OrderStatus.FILLED);
        }
    }

    private void matchLimitOrder(Order takerOrder, MatchingResult result) {
        boolean isBuy = takerOrder.getSide() == OrderSide.BUY;
        TreeMap<Long, PriceLevel> oppositeBook = isBuy ? asks : bids;
        
        Iterator<Map.Entry<Long, PriceLevel>> iterator = oppositeBook.entrySet().iterator();
        
        while (iterator.hasNext() && takerOrder.getRemainingQuantity() > 0) {
            Map.Entry<Long, PriceLevel> entry = iterator.next();
            long bestPrice = entry.getKey();
            
            if ((isBuy && takerOrder.getPrice() < bestPrice) || 
                (!isBuy && takerOrder.getPrice() > bestPrice)) {
                break; // No more matching prices
            }
            
            PriceLevel priceLevelQueue = entry.getValue();
            matchAgainstPriceLevel(takerOrder, priceLevelQueue, bestPrice, result);
            
            if (priceLevelQueue.isEmpty()) {
                iterator.remove();
            }
        }
        
        if (takerOrder.getRemainingQuantity() > 0) {
            takerOrder.setStatus(takerOrder.getFilledQuantity() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
            addOrderToBook(takerOrder);
            result.setOrderBookChanged(true);
        } else {
            takerOrder.setStatus(OrderStatus.FILLED);
        }
    }

    private void matchAgainstPriceLevel(Order takerOrder, PriceLevel priceLevelQueue, long matchPrice, MatchingResult result) {
        Order makerOrder = priceLevelQueue.head;
        
        while (makerOrder != null && takerOrder.getRemainingQuantity() > 0) {
            // Keep next ref here because we might remove makerOrder from list
            Order nextMaker = makerOrder.getNext();
            
            long tradeQuantity = Math.min(takerOrder.getRemainingQuantity(), makerOrder.getRemainingQuantity());
            
            takerOrder.setFilledQuantity(takerOrder.getFilledQuantity() + tradeQuantity);
            makerOrder.setFilledQuantity(makerOrder.getFilledQuantity() + tradeQuantity);
            
            if (makerOrder.getRemainingQuantity() == 0) {
                makerOrder.setStatus(OrderStatus.FILLED);
                priceLevelQueue.remove(makerOrder);
                activeOrders.remove(makerOrder.getId());
            } else {
                makerOrder.setStatus(OrderStatus.PARTIALLY_FILLED);
            }
            
            Trade trade = Trade.builder()
                .symbol(symbol)
                .price(matchPrice)
                .quantity(tradeQuantity)
                .makerOrderId(makerOrder.getId())
                .takerOrderId(takerOrder.getId())
                .makerAccountId(makerOrder.getAccountId())
                .takerAccountId(takerOrder.getAccountId())
                .takerSide(takerOrder.getSide())
                .takerPrice(takerOrder.getPrice())
                .timestampNanos(System.nanoTime())
                .build();
                
            result.addTrade(trade);
            result.setOrderBookChanged(true);
            
            makerOrder = nextMaker;
        }
    }

    private void addOrderToBook(Order order) {
        TreeMap<Long, PriceLevel> bookSide = order.getSide() == OrderSide.BUY ? bids : asks;
        PriceLevel level = bookSide.computeIfAbsent(order.getPrice(), k -> new PriceLevel());
        level.addLast(order);
        activeOrders.put(order.getId(), order);
    }

    public Order cancelOrder(long orderId, long accountId) {
        Order order = activeOrders.get(orderId);
        if (order == null) return null;
        if (order.getAccountId() != accountId) return null; // reject: not owner
        activeOrders.remove(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        TreeMap<Long, PriceLevel> bookSide = order.getSide() == OrderSide.BUY ? bids : asks;
        PriceLevel queue = bookSide.get(order.getPrice());
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                bookSide.remove(order.getPrice());
            }
        }
        return order;
    }

    /**
     * Returns number of active resting orders in this book.
     */
    public int getActiveOrderCount() {
        return activeOrders.size();
    }

    /**
     * Prune oldest orders from the book until active count is at or below maxOrders.
     * Removes from the worst price levels first (lowest bids, highest asks) since
     * those are furthest from mid-price and least likely to match.
     * Returns number of orders pruned.
     */
    public int pruneOldestOrders(int maxOrders) {
        int pruned = 0;
        int maxIterations = activeOrders.size(); // safety cap
        while (activeOrders.size() > maxOrders && maxIterations-- > 0) {
            boolean prunedAny = false;

            Map.Entry<Long, PriceLevel> worstBid = bids.isEmpty() ? null : bids.lastEntry();
            if (worstBid != null) {
                PriceLevel level = worstBid.getValue();
                if (level != null && level.head != null) {
                    Order order = level.head;
                    level.remove(order);
                    activeOrders.remove(order.getId());
                    order.setStatus(OrderStatus.CANCELLED);
                    pruned++;
                    prunedAny = true;
                }
                if (level == null || level.isEmpty()) {
                    bids.remove(worstBid.getKey());
                }
            }

            if (activeOrders.size() <= maxOrders) break;

            Map.Entry<Long, PriceLevel> worstAsk = asks.isEmpty() ? null : asks.lastEntry();
            if (worstAsk != null) {
                PriceLevel level = worstAsk.getValue();
                if (level != null && level.head != null) {
                    Order order = level.head;
                    level.remove(order);
                    activeOrders.remove(order.getId());
                    order.setStatus(OrderStatus.CANCELLED);
                    pruned++;
                    prunedAny = true;
                }
                if (level == null || level.isEmpty()) {
                    asks.remove(worstAsk.getKey());
                }
            }

            // If we couldn't prune from either side, stop to avoid infinite loop
            if (!prunedAny) break;
        }
        return pruned;
    }
}
