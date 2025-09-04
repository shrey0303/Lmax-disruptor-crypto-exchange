package shrey.exchange.transport.grpc;

import shrey.bank.proto.MarketDataServiceGrpc;
import shrey.bank.proto.TradingProto;
import shrey.exchange.domain.TradingWallets;
import shrey.exchange.cluster.ClusterStatus;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author shrey
 * @since 2024
 */
@Slf4j
@RequiredArgsConstructor
public class MarketDataGrpcService extends MarketDataServiceGrpc.MarketDataServiceImplBase {

    private final TradingWallets tradingWallets;
    private final shrey.exchange.domain.OrderBookManager orderBookManager;

    @Override
    public StreamObserver<TradingProto.MarketDataQuery> subscribe(StreamObserver<TradingProto.MarketDataResult> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(TradingProto.MarketDataQuery query) {
                if (ClusterStatus.STATE.get().equals(ClusterStatus.NOT_AVAILABLE)) {
                    responseObserver.onNext(
                        TradingProto.MarketDataResult.newBuilder()
                            .setBaseResult(
                                TradingProto.TradingBaseResult.newBuilder()
                                    .setCode(503)
                                    .setMessage("Service not available")
                                    .build()
                            )
                            .build()
                    );
                    return;
                }
                
                switch (query.getTypeCase()) {
                    case ORDERBOOKQUERY -> {
                        var obQuery = query.getOrderBookQuery();
                        var orderBook = orderBookManager.getOrderBook(obQuery.getSymbol());
                        
                        var resultBuilder = TradingProto.MarketDataResult.newBuilder();
                        
                        if (orderBook == null) {
                            resultBuilder.setBaseResult(
                                TradingProto.TradingBaseResult.newBuilder()
                                    .setCode(404)
                                    .setMessage("OrderBook not found for symbol: " + obQuery.getSymbol())
                                    .setCorrelationId(obQuery.getCorrelationId())
                                    .build()
                            );
                        } else {
                            var snapshotBuilder = TradingProto.OrderBookSnapshot.newBuilder()
                                .setSymbol(obQuery.getSymbol())
                                .setTimestampNanos(System.nanoTime());
                                
                            int depth = obQuery.getDepth() > 0 ? obQuery.getDepth() : 50;
                            
                            var asks = orderBook.getAsks().entrySet().iterator();
                            for (int i = 0; i < depth && asks.hasNext(); i++) {
                                var entry = asks.next();
                                var level = entry.getValue();
                                snapshotBuilder.addAsks(TradingProto.PriceLevel.newBuilder()
                                    .setPrice(level.price)
                                    .setQuantity(level.getTotalQuantity())
                                    .setOrderCount(level.getOrderCount())
                                    .build());
                            }
                            
                            var bids = orderBook.getBids().entrySet().iterator();
                            for (int i = 0; i < depth && bids.hasNext(); i++) {
                                var entry = bids.next();
                                var level = entry.getValue();
                                snapshotBuilder.addBids(TradingProto.PriceLevel.newBuilder()
                                    .setPrice(level.price)
                                    .setQuantity(level.getTotalQuantity())
                                    .setOrderCount(level.getOrderCount())
                                    .build());
                            }
                            
                            resultBuilder.setOrderBookSnapshot(snapshotBuilder.build());
                        }
                        responseObserver.onNext(resultBuilder.build());
                    }
                    case TICKERQUERY -> {
                        responseObserver.onNext(
                            TradingProto.MarketDataResult.newBuilder()
                                .setBaseResult(
                                    TradingProto.TradingBaseResult.newBuilder()
                                        .setCode(501)
                                        .setMessage("Ticker queries not implemented yet")
                                        .setCorrelationId(query.getTickerQuery().getCorrelationId())
                                        .build()
                                )
                                .build()
                        );
                    }
                    case TYPE_NOT_SET -> {}
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Error in MarketDataGrpcService: {}", throwable.getMessage());
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                log.info("Client completed MarketData subscription");
                responseObserver.onCompleted();
            }
        };
    }
}
