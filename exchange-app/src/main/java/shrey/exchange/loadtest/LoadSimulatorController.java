package shrey.exchange.loadtest;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shrey.bank.proto.TradingCommandServiceGrpc;
import shrey.bank.proto.TradingProto;
import shrey.exchange.domain.TradingWallets;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/v1/loadtest")
public class LoadSimulatorController {

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final TradingWallets tradingWallets;
    private ManagedChannel channel;
    private StreamObserver<TradingProto.TradingCommand> commandStream;
    private Thread generatorThread;
    private boolean accountsSeeded = false;

    public LoadSimulatorController(TradingWallets tradingWallets) {
        this.tradingWallets = tradingWallets;
    }

    @PostMapping("/start")
    public String startLoadTest(@RequestParam(defaultValue = "1000") int ordersPerSecond) {
        if (isRunning.compareAndSet(false, true)) {

            // Seed accounts directly in-JVM — bypasses the entire Disruptor pipeline
            if (!accountsSeeded) {
                for (int i = 0; i < 100; i++) {
                    long id = tradingWallets.newTradingAccount();
                    tradingWallets.deposit(id, "USDT", 100_000_000L);
                    tradingWallets.deposit(id, "BTC", 10_000_000L);
                }
                accountsSeeded = true;
                log.info("Pre-seeded 100 trading accounts with USDT and BTC balances");
            }

            channel = ManagedChannelBuilder.forAddress("localhost", 9500)
                    .usePlaintext()
                    .build();
            TradingCommandServiceGrpc.TradingCommandServiceStub stub = TradingCommandServiceGrpc.newStub(channel);

            commandStream = stub.sendCommand(new StreamObserver<>() {
                @Override
                public void onNext(TradingProto.TradingResponse value) { }
                @Override
                public void onError(Throwable t) { log.error("LoadTest gRPC error", t); }
                @Override
                public void onCompleted() { }
            });

            generatorThread = new Thread(() -> {
                try {
                    long intervalNanos = 1_000_000_000L / ordersPerSecond;
                    long nextTick = System.nanoTime() + intervalNanos;

                    while (isRunning.get()) {
                        generateSingleOrder();

                        long now = System.nanoTime();
                        if (now < nextTick) {
                            java.util.concurrent.locks.LockSupport.parkNanos(nextTick - now);
                        }
                        nextTick += intervalNanos;
                    }
                } catch (Exception e) {
                    log.error("Simulator thread error", e);
                }
            }, "load-sim-generator");
            generatorThread.start();

            return "Load test started at " + ordersPerSecond + " ops/sec";
        }
        return "Already running";
    }

    @PostMapping("/stop")
    public String stopLoadTest() {
        if (isRunning.compareAndSet(true, false)) {
            if (generatorThread != null) {
                try { generatorThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (commandStream != null) {
                try { commandStream.onCompleted(); } catch (Exception e) { log.warn("Error completing command stream", e); }
            }
            if (channel != null) {
                channel.shutdown();
            }
            return "Load test stopped";
        }
        return "Not running";
    }

    private void generateSingleOrder() {
        Random random = new Random();
        try {
            long price = 50000L + random.nextInt(1000) - 500;

            TradingProto.PlaceOrderCommand placeOrder = TradingProto.PlaceOrderCommand.newBuilder()
                .setCorrelationId("sim-" + System.nanoTime())
                .setAccountId((long)(random.nextInt(100) + 1))
                .setSymbol("BTC_USDT")
                .setSide(random.nextBoolean() ? TradingProto.OrderSide.BUY : TradingProto.OrderSide.SELL)
                .setOrderType(random.nextInt(10) > 7 ? TradingProto.OrderType.MARKET : TradingProto.OrderType.LIMIT)
                .setPrice(price)
                .setQuantity((long)(random.nextInt(50) + 1))
                .setTimeInForce(TradingProto.TimeInForce.GTC)
                .setTimestampNanos(System.nanoTime())
                .build();

            TradingProto.TradingCommand cmd = TradingProto.TradingCommand.newBuilder().setPlaceOrderCommand(placeOrder).build();
            commandStream.onNext(cmd);
        } catch (Exception e) {
            log.warn("Error sending simulated order", e);
        }
    }
}
