package shrey.exchange.transport.grpc;

import com.google.common.util.concurrent.MoreExecutors;
import shrey.exchange.domain.TradingWallets;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * @author shrey
 * @since 2024
 */
@Slf4j
@Profile("follower")
@Component
@RequiredArgsConstructor
public class TradingGrpcResourceFollower {
    private final TradingWallets tradingWallets;
    private final shrey.exchange.domain.OrderBookManager orderBookManager;
    private Server server;

    @Value("${server.grpc.port}")
    private int serverPort;

    @PostConstruct
    void init() {
        try {
            MarketDataGrpcService marketDataGrpcService = new MarketDataGrpcService(tradingWallets, orderBookManager);
            server = ServerBuilder.forPort(serverPort)
                .addService(marketDataGrpcService)
                .executor(MoreExecutors.directExecutor())
                .build();
            server.start();
        } catch (Exception e) {
            log.error("Failed to start gRPC server", e);
            System.exit(-9);
        }
    }

    @PreDestroy
    void destroy() {
        if (server != null) {
            server.shutdown();
        }
    }
}
