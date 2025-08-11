package shrey.exchange.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import shrey.bank.proto.TradingProto.OrderSide;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trade {
    private long id;
    private String symbol;
    private long price;
    private long quantity;
    private long makerOrderId;
    private long takerOrderId;
    private long makerAccountId;
    private long takerAccountId;
    private OrderSide takerSide;
    private long takerPrice; // taker's original limit price, for excess hold release
    private long timestampNanos;
}
