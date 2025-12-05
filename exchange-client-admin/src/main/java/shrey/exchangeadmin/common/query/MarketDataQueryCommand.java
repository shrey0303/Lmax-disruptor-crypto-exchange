package shrey.exchangeadmin.common.query;

import lombok.Getter;
import lombok.Setter;
import shrey.exchangeclient.cluster.BaseRequest;

import java.util.UUID;

@Getter
@Setter
public abstract class MarketDataQueryCommand implements BaseRequest {
    private String correlationId = UUID.randomUUID().toString();
}
