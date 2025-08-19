package shrey.exchange;

import lombok.Data;
import lombok.NoArgsConstructor;
import shrey.exchange.domain.Order;
import shrey.exchange.domain.MatchingResult;

/**
 * @author shrey
 * @since 2024
 */
@Data
@NoArgsConstructor
public class CommandBufferEvent implements BufferEvent {
    private String replyChannel;
    private String correlationId;

    private BaseCommand command;
    private BaseResult result;

    private Order order;
    private MatchingResult matchingResult;
    private boolean isRejected;

    private long entryTimestampNanos;
    private long riskFinishedNanos;
    private long matchFinishedNanos;

    public CommandBufferEvent(String replyChannel, String correlationId, BaseCommand command) {
        this.replyChannel = replyChannel;
        this.correlationId = correlationId;
        this.command = command;
        this.entryTimestampNanos = System.nanoTime(); // capture entry time
    }

    public void copy(CommandBufferEvent event) {
        this.replyChannel = event.replyChannel;
        this.correlationId = event.correlationId;
        this.command = event.command;
        this.result = event.result;
        this.order = event.order;
        this.matchingResult = event.matchingResult;
        this.isRejected = event.isRejected;
        
        this.entryTimestampNanos = event.entryTimestampNanos;
        this.riskFinishedNanos = event.riskFinishedNanos;
        this.matchFinishedNanos = event.matchFinishedNanos;
    }
}
