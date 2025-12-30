package shrey.benchmarkbinance;

import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import shrey.exchange.CommandBufferEvent;

/**
 * Terminal handler in the Disruptor pipeline.
 * Captures end-of-pipeline timestamp and feeds latency data into SimulationMetrics.
 */
@RequiredArgsConstructor
public class MetricsReplyHandler implements EventHandler<CommandBufferEvent> {

    private final SimulationMetrics metrics;

    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) {
        long exitNanos = System.nanoTime();

        metrics.recordEvent(
            event.getEntryTimestampNanos(),
            event.getRiskFinishedNanos(),
            event.getMatchFinishedNanos(),
            exitNanos,
            event.getMatchingResult(),
            event.isRejected()
        );

        // Reset event state for ring buffer reuse
        event.setRejected(false);
        event.setOrder(null);
        event.setMatchingResult(null);
        event.setCommand(null);
        event.setResult(null);
        event.setEntryTimestampNanos(0);
        event.setRiskFinishedNanos(0);
        event.setMatchFinishedNanos(0);
    }
}
