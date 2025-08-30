package shrey.exchange;

import lombok.RequiredArgsConstructor;

/**
 * @author shrey
 * @since 2024
 */
@RequiredArgsConstructor
public class CommandBufferReplyImpl implements CommandBufferReply {
    private final ReplyBufferEventDispatcher replyBufferEventDispatcher;
    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        replyBufferEventDispatcher.dispatch(
            new ReplyBufferEvent(
                event.getReplyChannel(),
                event.getCorrelationId(),
                event.getResult()
            )
        );
    }
}
