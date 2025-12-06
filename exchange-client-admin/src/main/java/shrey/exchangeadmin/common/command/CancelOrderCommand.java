package shrey.exchangeadmin.common.command;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class CancelOrderCommand extends TradingCommand {
    private long accountId;
    private long orderId;
    private String symbol;
}
