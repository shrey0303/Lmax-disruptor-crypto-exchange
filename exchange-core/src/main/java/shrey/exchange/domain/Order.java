package shrey.exchange.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import shrey.bank.proto.TradingProto.OrderSide;
import shrey.bank.proto.TradingProto.OrderStatus;
import shrey.bank.proto.TradingProto.OrderType;
import shrey.bank.proto.TradingProto.TimeInForce;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private long id;
    private long accountId;
    private String symbol;
    private OrderSide side;
    private OrderType type;
    private long price;
    private long quantity;
    private long filledQuantity;
    private TimeInForce timeInForce;
    private OrderStatus status;
    private long timestampNanos;
    
    @lombok.ToString.Exclude
    private Order next;
    
    @lombok.ToString.Exclude
    private Order prev;
    
    public long getRemainingQuantity() {
        return quantity - filledQuantity;
    }
}
