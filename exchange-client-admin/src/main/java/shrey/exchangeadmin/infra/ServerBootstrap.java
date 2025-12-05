package shrey.exchangeadmin.infra;

import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import shrey.bank.proto.MarketDataServiceGrpc;
import shrey.bank.proto.TradingCommandServiceGrpc;
import shrey.exchangeclient.cluster.*;

@Configuration
public class ServerBootstrap {
    @Value("${cluster.leader.host:localhost}")
    private String leaderHost;

    @Value("${cluster.leader.port:9500}")
    private int leaderPort;

    @Value("${request-buffer.pow:10}")
    private int requestBufferPow;

    private Disruptor<RequestBufferEvent> tradingCommandBufferDisruptor;

    @EventListener(ApplicationReadyEvent.class)
    void startServer() {
        tradingCommandBufferDisruptor.start();
    }

    @Bean
    TradingCommandServiceGrpc.TradingCommandServiceStub tradingCommandServiceStub() {
        return TradingCommandServiceGrpc.newStub(ManagedChannelBuilder.forAddress(leaderHost, leaderPort).usePlaintext().build());
    }

    @Bean
    MarketDataServiceGrpc.MarketDataServiceStub marketDataServiceStub() {
        return MarketDataServiceGrpc.newStub(ManagedChannelBuilder.forAddress(leaderHost, leaderPort).usePlaintext().build());
    }

    @Bean
    Disruptor<RequestBufferEvent> tradingCommandBufferDisruptor(TradingRequestBufferHandler handler) {
        tradingCommandBufferDisruptor = new RequestBufferDisruptorDSL(handler).build(1 << requestBufferPow, new SleepingWaitStrategy());
        return tradingCommandBufferDisruptor;
    }

    @Bean
    RequestBufferDispatcher<BaseRequest> requestBufferDispatcher(
        Disruptor<RequestBufferEvent> disruptor,
        @Value("${request.timeout.milliseconds:5000}") long timeoutMilliseconds
    ) {
        return new RequestBufferDispatcherImpl<>(disruptor, timeoutMilliseconds);
    }

    @Bean
    TradingCommandStub tradingCommandStub(TradingCommandServiceGrpc.TradingCommandServiceStub stub) {
        return new TradingCommandStub(stub);
    }

    @Bean
    MarketDataStub marketDataStub(MarketDataServiceGrpc.MarketDataServiceStub stub) {
        return new MarketDataStub(stub);
    }

    @Bean
    TradingRequestBufferHandler tradingRequestBufferHandler(TradingCommandStub cmdStub, MarketDataStub mdStub) {
        return new TradingRequestBufferHandler(cmdStub, mdStub);
    }
}
