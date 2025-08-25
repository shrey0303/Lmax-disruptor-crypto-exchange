package shrey.exchange;

import com.lmax.disruptor.EventHandler;

/**
 * @author shrey
 * @since 2024
 */
public interface CommandBufferJournaler extends EventHandler<CommandBufferEvent>, CommandBufferChannel {
}
