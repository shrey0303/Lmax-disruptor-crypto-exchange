package shrey.exchangeadmin.common.query;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderBookQueryCommand extends MarketDataQueryCommand {
    private String symbol;
    private int depth;
}
