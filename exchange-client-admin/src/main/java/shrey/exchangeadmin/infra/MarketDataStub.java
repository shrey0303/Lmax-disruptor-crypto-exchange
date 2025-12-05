package shrey.exchangeadmin.infra;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import shrey.bank.proto.MarketDataServiceGrpc;
import shrey.bank.proto.TradingProto;
import shrey.exchangeadmin.common.query.OrderBookQueryCommand;
import shrey.exchangeadmin.common.response.MarketDataResponse;
import shrey.exchangeclient.cluster.BaseAsyncStub;
import shrey.exchangeclient.cluster.BaseResponse;
import shrey.exchangeclient.cluster.RequestBufferEvent;

@Slf4j
public class MarketDataStub extends BaseAsyncStub<TradingProto.MarketDataQuery, TradingProto.MarketDataResult> {

    private final MarketDataServiceGrpc.MarketDataServiceStub marketDataServiceStub;

    public MarketDataStub(MarketDataServiceGrpc.MarketDataServiceStub marketDataServiceStub) {
        this.marketDataServiceStub = marketDataServiceStub;
        this.initRequestStreamObserver();
    }

    @Override
    protected StreamObserver<TradingProto.MarketDataQuery> initRequestStreamObserver(StreamObserver<TradingProto.MarketDataResult> responseObserver) {
        return marketDataServiceStub.subscribe(responseObserver);
    }

    @Override
    protected void sendGrpcMessage(RequestBufferEvent request) {
        try {
            replyFutures.put(request.getRequest().getCorrelationId(), request.getResponseFuture());
            if (request.getRequest() instanceof OrderBookQueryCommand obQuery) {
                queryOrderBook(obQuery);
            } else {
                replyFutures.remove(request.getRequest().getCorrelationId());
            }
        } catch (Exception e) {
            log.error("Error while sending market data query", e);
            replyFutures.remove(request.getRequest().getCorrelationId());
            throw e;
        }
    }

    @Override
    protected String extractResultCorrelationId(TradingProto.MarketDataResult result) {
        if (result.hasBaseResult() && result.getBaseResult().hasCorrelationId()) {
            return result.getBaseResult().getCorrelationId();
        }
        // Since snapshot does not have correlation ID in the current proto design natively,
        // we might not map it perfectly if multiple concurrent requests happen without changes. 
        // For simplicity, we just return empty if not found, though this means the future may timeout.
        return ""; 
    }

    @Override
    protected BaseResponse extractResult(TradingProto.MarketDataResult result) {
        if (result.hasBaseResult()) {
            return new MarketDataResponse(result.getBaseResult().getCode(), result.getBaseResult().getMessage(), null);
        } else if (result.hasOrderBookSnapshot()) {
            // Success response
            return new MarketDataResponse(200, "Success", result.getOrderBookSnapshot());
        }
        return new MarketDataResponse(500, "Unknown result type", null);
    }

    private void queryOrderBook(OrderBookQueryCommand cmd) {
        var protoCmd = TradingProto.OrderBookQuery.newBuilder()
            .setCorrelationId(cmd.getCorrelationId())
            .setSymbol(cmd.getSymbol())
            .setDepth(cmd.getDepth())
            .build();
            
        requestStreamObserver.onNext(TradingProto.MarketDataQuery.newBuilder().setOrderBookQuery(protoCmd).build());
    }
}
