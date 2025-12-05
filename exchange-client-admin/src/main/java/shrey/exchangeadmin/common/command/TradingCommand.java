package shrey.exchangeadmin.common.command;

import lombok.Getter;
import lombok.Setter;
import shrey.exchangeclient.cluster.BaseRequest;
import java.util.UUID;

@Getter
@Setter
public abstract class TradingCommand implements BaseRequest {
    private String correlationId = UUID.randomUUID().toString();
}
