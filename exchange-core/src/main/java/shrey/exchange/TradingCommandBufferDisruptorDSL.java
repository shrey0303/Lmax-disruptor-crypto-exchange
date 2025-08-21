package shrey.exchange;

import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import lombok.RequiredArgsConstructor;

/**
 * 4-stage Trading Disruptor Pipeline
 */
@RequiredArgsConstructor
public class TradingCommandBufferDisruptorDSL implements DisruptorDSL<CommandBufferEvent> {
    private final RiskValidationHandler riskValidationHandler;
    private final CommandBufferJournaler tradingJournaler;
    private final MatchingHandler matchingHandler;
    private final SettlementHandler settlementHandler;
    private final CommandBufferReply commandBufferReply;

    @Override
    public Disruptor<CommandBufferEvent> build(int bufferSize, WaitStrategy waitStrategy) {
        var disruptor = new Disruptor<>(
            CommandBufferEvent::new,
            bufferSize,
            DaemonThreadFactory.INSTANCE,
            ProducerType.MULTI,
            waitStrategy
        );
        
        disruptor.handleEventsWith(riskValidationHandler)
                 .then(tradingJournaler)
                 .then(matchingHandler)
                 .then(settlementHandler)
                 .then(commandBufferReply);
                 
        return disruptor;
    }
}
