package shrey.benchmarkbinance;

import com.lmax.disruptor.EventHandler;
import shrey.exchange.CommandBufferEvent;

/**
 * No-op journaler for benchmarking — skips Kafka entirely.
 * In the real pipeline this slot is CommandBufferJournalerImpl which writes to Kafka.
 * For benchmark purposes we only care about matching engine latency.
 */
public class NoopJournaler implements EventHandler<CommandBufferEvent> {

    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) {
        // intentionally empty — no journaling overhead in benchmark
    }
}
