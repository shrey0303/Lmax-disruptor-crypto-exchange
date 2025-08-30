package shrey.exchange;

import com.lmax.disruptor.dsl.Disruptor;
import lombok.RequiredArgsConstructor;

/**
 * @author shrey
 * @since 2024
 */
@RequiredArgsConstructor
public class ReplyBufferEventDispatcherImpl implements ReplyBufferEventDispatcher {

    private final Disruptor<ReplyBufferEvent> replyBufferEventDisruptor;

    @Override
    public void dispatch(ReplyBufferEvent replyBufferEvent) {
        replyBufferEventDisruptor.publishEvent((event, sequence) -> {
            event.setCorrelationId(replyBufferEvent.getCorrelationId());
            event.setResult(replyBufferEvent.getResult());
            event.setReplyChannel(replyBufferEvent.getReplyChannel());
        });
    }
}
