package shrey.exchangeadmin.infra;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import shrey.bank.proto.TradingCommandServiceGrpc;
import shrey.bank.proto.TradingProto;
import shrey.exchangeadmin.common.command.CancelOrderCommand;
import shrey.exchangeadmin.common.command.PlaceOrderCommand;
import shrey.exchangeclient.cluster.BaseAsyncStub;
import shrey.exchangeclient.cluster.BaseResponse;
import shrey.exchangeclient.cluster.RequestBufferEvent;

@Slf4j
public class TradingCommandStub extends BaseAsyncStub<TradingProto.TradingCommand, TradingProto.TradingResponse> {

    private final TradingCommandServiceGrpc.TradingCommandServiceStub commandServiceStub;

    public TradingCommandStub(TradingCommandServiceGrpc.TradingCommandServiceStub commandServiceStub) {
        this.commandServiceStub = commandServiceStub;
        this.initRequestStreamObserver();
    }

    @Override
    protected StreamObserver<TradingProto.TradingCommand> initRequestStreamObserver(StreamObserver<TradingProto.TradingResponse> responseObserver) {
        return commandServiceStub.sendCommand(responseObserver);
    }

    @Override
    protected void sendGrpcMessage(RequestBufferEvent request) {
        try {
            replyFutures.put(request.getRequest().getCorrelationId(), request.getResponseFuture());
            switch (request.getRequest()) {
                case PlaceOrderCommand placeCmd -> placeOrder(placeCmd);
                case CancelOrderCommand cancelCmd -> cancelOrder(cancelCmd);
                default -> replyFutures.remove(request.getRequest().getCorrelationId());
            }
        } catch (Exception e) {
            log.error("Error while sending trading message", e);
            replyFutures.remove(request.getRequest().getCorrelationId());
            throw e;
        }
    }

    @Override
    protected String extractResultCorrelationId(TradingProto.TradingResponse result) {
        if (result.hasOrderAck()) {
            return result.getOrderAck().getCorrelationId();
        } else if (result.hasBaseResult()) {
            return result.getBaseResult().getCorrelationId();
        }
        return "";
    }

    @Override
    protected BaseResponse extractResult(TradingProto.TradingResponse result) {
        if (result.hasOrderAck()) {
            return new BaseResponse(200, "Order " + result.getOrderAck().getStatus().name() + " id=" + result.getOrderAck().getOrderId());
        }
        return new BaseResponse(result.getBaseResult().getCode(), result.getBaseResult().getMessage());
    }

    private void placeOrder(PlaceOrderCommand cmd) {
        var protoCmd = TradingProto.PlaceOrderCommand.newBuilder()
            .setCorrelationId(cmd.getCorrelationId())
            .setAccountId(cmd.getAccountId())
            .setSymbol(cmd.getSymbol())
            .setSide(TradingProto.OrderSide.valueOf(cmd.getSide()))
            .setOrderType(TradingProto.OrderType.valueOf(cmd.getType()))
            .setPrice(cmd.getPrice())
            .setQuantity(cmd.getQuantity())
            .setTimeInForce(TradingProto.TimeInForce.GTC)
            .setTimestampNanos(System.nanoTime())
            .build();
            
        requestStreamObserver.onNext(TradingProto.TradingCommand.newBuilder().setPlaceOrderCommand(protoCmd).build());
    }

    private void cancelOrder(CancelOrderCommand cmd) {
        var protoCmd = TradingProto.CancelOrderCommand.newBuilder()
            .setCorrelationId(cmd.getCorrelationId())
            .setAccountId(cmd.getAccountId())
            .setSymbol(cmd.getSymbol())
            .setOrderId(cmd.getOrderId())
            .setTimestampNanos(System.nanoTime())
            .build();
            
        requestStreamObserver.onNext(TradingProto.TradingCommand.newBuilder().setCancelOrderCommand(protoCmd).build());
    }
}
