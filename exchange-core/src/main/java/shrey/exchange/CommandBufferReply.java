package shrey.exchange;

import com.lmax.disruptor.EventHandler;

/**
 * Send to outbound buffers
 *
 * @author shrey
 * @since 2024
 */
public interface CommandBufferReply extends EventHandler<CommandBufferEvent>, CommandBufferChannel {
}
