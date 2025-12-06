package shrey.exchangeadmin.common.command;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class PlaceOrderCommand extends TradingCommand {
    private long accountId;
    private String symbol;
    private String side; // "BUY" or "SELL"
    private String type; // "LIMIT" or "MARKET"
    private long price;
    private long quantity;
}
