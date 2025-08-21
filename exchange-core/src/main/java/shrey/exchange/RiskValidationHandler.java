package shrey.exchange;

import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shrey.bank.proto.TradingProto;
import shrey.exchange.domain.Order;
import shrey.exchange.domain.RiskEngine;
import shrey.exchange.domain.TradingWallets;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class RiskValidationHandler implements EventHandler<CommandBufferEvent> {

    private final TradingWallets wallets;
    private final RiskEngine riskEngine;
    private final AtomicLong orderIdCounter = new AtomicLong(0);
    private long rejectionCount = 0;
    private static final long LOG_EVERY_N_REJECTIONS = 10_000;

    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event.getCommand() == null || event.getCommand().getCommandLog() == null) {
            return;
        }
        
        try {
            switch (event.getCommand().getCommandLog().getTypeCase()) {
                case PLACEORDERCOMMAND -> {
                    TradingProto.PlaceOrderCommand command = event.getCommand().getCommandLog().getPlaceOrderCommand();
                    long orderId = orderIdCounter.incrementAndGet();
                    
                    Order order = Order.builder()
                        .id(orderId)
                        .accountId(command.getAccountId())
                        .symbol(command.getSymbol())
                        .side(command.getSide())
                        .type(command.getOrderType())
                        .price(command.getPrice())
                        .quantity(command.getQuantity())
                        .filledQuantity(0)
                        .timeInForce(command.getTimeInForce())
                        .status(TradingProto.OrderStatus.NEW)
                        .timestampNanos(command.getTimestampNanos())
                        .build();
                        
                    event.setOrder(order);
                    event.setRejected(false);
                    
                    try {
                        riskEngine.validateOrderPlacement(order, wallets);
                        riskEngine.holdAssetsForOrder(order, wallets);
                    } catch (Exception e) {
                        event.setRejected(true);
                        rejectionCount++;
                        if (rejectionCount % LOG_EVERY_N_REJECTIONS == 0) {
                            log.warn("Risk rejection #{}: {}", rejectionCount, e.getMessage());
                        }
                    }
                }
                case CANCELORDERCOMMAND -> {
                    // Risk check for cancel is deferred to matching
                }
                default -> {}
            }
        } catch (Exception e) {
            event.setRejected(true);
            log.error("Error in RiskValidationHandler", e);
        } finally {
            event.setRiskFinishedNanos(System.nanoTime());
        }
    }
}
