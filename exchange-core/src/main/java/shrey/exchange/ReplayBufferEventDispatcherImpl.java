package shrey.exchange;

import com.lmax.disruptor.dsl.Disruptor;
import lombok.RequiredArgsConstructor;

/**
 * @author shrey
 * @since 2024
 */
@RequiredArgsConstructor
public class ReplayBufferEventDispatcherImpl implements ReplayBufferEventDispatcher {
    private final Disruptor<ReplayBufferEvent> replayBufferEventDisruptor;

    @Override
    public void dispatch(ReplayBufferEvent replayBufferEvent) {
        replayBufferEventDisruptor.publishEvent(((event, sequence) -> event.setCommand(replayBufferEvent.getCommand())));
    }
}
