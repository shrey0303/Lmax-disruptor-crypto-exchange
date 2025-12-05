package shrey.exchangeadmin.infra;

import shrey.exchangeadmin.common.command.TradingCommand;
import shrey.exchangeadmin.common.query.MarketDataQueryCommand;
import shrey.exchangeclient.cluster.RequestBufferEvent;
import shrey.exchangeclient.cluster.RequestBufferHandler;

public class TradingRequestBufferHandler implements RequestBufferHandler {

    private final TradingCommandStub tradingCommandStub;
    private final MarketDataStub marketDataStub;

    public TradingRequestBufferHandler(TradingCommandStub tradingCommandStub, MarketDataStub marketDataStub) {
        this.tradingCommandStub = tradingCommandStub;
        this.marketDataStub = marketDataStub;
    }

    @Override
    public void onEvent(RequestBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event.getRequest() instanceof TradingCommand) {
            tradingCommandStub.sendGrpcMessage(event);
        } else if (event.getRequest() instanceof MarketDataQueryCommand) {
            marketDataStub.sendGrpcMessage(event);
        }
    }
}
