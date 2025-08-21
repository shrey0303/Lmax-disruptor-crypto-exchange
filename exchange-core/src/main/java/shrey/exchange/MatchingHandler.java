package shrey.exchange;

import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shrey.exchange.domain.MatchingResult;
import shrey.exchange.domain.Order;
import shrey.exchange.domain.OrderBookManager;

@Slf4j
@RequiredArgsConstructor
public class MatchingHandler implements EventHandler<CommandBufferEvent> {

    private final OrderBookManager orderBookManager;

    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event.isRejected()) return; // skip if already rejected by risk
        
        try {
            switch (event.getCommand().getCommandLog().getTypeCase()) {
                case PLACEORDERCOMMAND -> {
                    Order order = event.getOrder();
                    if (order != null) {
                        MatchingResult result = orderBookManager.processOrder(order);
                        event.setMatchingResult(result);
                    }
                }
                case CANCELORDERCOMMAND -> {
                    var command = event.getCommand().getCommandLog().getCancelOrderCommand();
                    Order cancelledOrder = orderBookManager.cancelOrder(
                            command.getSymbol(), command.getOrderId(), command.getAccountId());
                    if (cancelledOrder == null) {
                        event.setRejected(true);
                    } else {
                        event.setOrder(cancelledOrder); // pass to settlement for hold release
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            event.setRejected(true);
            log.error("Error in MatchingHandler", e);
        } finally {
            event.setMatchFinishedNanos(System.nanoTime());
        }
    }
}
