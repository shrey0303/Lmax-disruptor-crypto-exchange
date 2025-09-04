package shrey.exchange.transport.grpc;

import shrey.bank.proto.TradingCommandServiceGrpc;
import shrey.bank.proto.TradingProto;
import shrey.exchange.infra.SimpleReplier;
import shrey.exchange.BaseCommand;
import shrey.exchange.CommandBufferEvent;
import shrey.exchange.CommandBufferEventDispatcher;
import shrey.exchange.cluster.ClusterStatus;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * @author shrey
 * @since 2024
 */
@Slf4j
@RequiredArgsConstructor
public class TradingGrpcCommand extends TradingCommandServiceGrpc.TradingCommandServiceImplBase {

    private final CommandBufferEventDispatcher commandBufferEventDispatcher;
    private final SimpleReplier simpleReplier;

    @Override
    public StreamObserver<TradingProto.TradingCommand> sendCommand(StreamObserver<TradingProto.TradingResponse> responseObserver) {
        var replyChannel = UUID.randomUUID().toString();
        simpleReplier.repliers.put(replyChannel, responseObserver);
        return new StreamObserver<>() {
            @Override
            public void onNext(TradingProto.TradingCommand tradingCommand) {
                if (ClusterStatus.STATE.get().equals(ClusterStatus.NOT_AVAILABLE)) {
                    responseError(responseObserver, 503, "Service not available", null);
                }
                try {
                    switch (tradingCommand.getTypeCase()) {
                        case PLACEORDERCOMMAND -> placeOrder(replyChannel, tradingCommand);
                        case CANCELORDERCOMMAND -> cancelOrder(replyChannel, tradingCommand);
                        default -> responseError(responseObserver, 400, "Invalid command", null);
                    }
                } catch (Exception e) {
                    responseError(responseObserver, 500, "Internal server error", e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("{} on command streaming error {}", replyChannel, throwable.getMessage());
                simpleReplier.repliers.remove(replyChannel);
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                log.info("{} on completed command streaming", replyChannel);
                simpleReplier.repliers.remove(replyChannel);
                responseObserver.onCompleted();
            }
        };
    }

    private void placeOrder(String replyChannel, TradingProto.TradingCommand tradingCommand) {
        commandBufferEventDispatcher.dispatch(
            new CommandBufferEvent(
                replyChannel,
                tradingCommand.getPlaceOrderCommand().getCorrelationId(),
                new BaseCommand(TradingProto.TradingCommandLog.newBuilder()
                    .setPlaceOrderCommand(tradingCommand.getPlaceOrderCommand())
                    .build()
                )
            )
        );
    }

    private void cancelOrder(String replyChannel, TradingProto.TradingCommand tradingCommand) {
        commandBufferEventDispatcher.dispatch(
            new CommandBufferEvent(
                replyChannel,
                tradingCommand.getCancelOrderCommand().getCorrelationId(),
                new BaseCommand(TradingProto.TradingCommandLog.newBuilder()
                    .setCancelOrderCommand(tradingCommand.getCancelOrderCommand())
                    .build()
                )
            )
        );
    }

    private void responseError(StreamObserver<TradingProto.TradingResponse> responseObserver, int code, String message, Exception e) {
        if (e != null) {
            log.error(message, e);
        }
        responseObserver.onNext(
            TradingProto.TradingResponse.newBuilder()
                .setBaseResult(
                    TradingProto.TradingBaseResult.newBuilder()
                        .setCode(code)
                        .setMessage(message)
                        .build()
                )
                .build()
        );
    }
}
