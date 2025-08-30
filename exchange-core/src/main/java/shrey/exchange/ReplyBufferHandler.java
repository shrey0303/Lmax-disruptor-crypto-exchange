package shrey.exchange;

import com.lmax.disruptor.EventHandler;

/**
 * @author shrey
 * @since 2024
 */
public interface ReplyBufferHandler extends EventHandler<ReplyBufferEvent>, ReplyBufferChanel {
}
