package shrey.exchangeclient.cluster;

import com.lmax.disruptor.EventHandler;

/**
 * @author shrey
 * @since 2024
 */
public interface RequestBufferHandler extends EventHandler<RequestBufferEvent>, RequestBufferChannel {
}
