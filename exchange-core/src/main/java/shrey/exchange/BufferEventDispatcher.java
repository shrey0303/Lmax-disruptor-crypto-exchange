package shrey.exchange;

/**
 * @author shrey
 * @since 2024
 */
public interface BufferEventDispatcher<T extends BufferEvent> {
    void dispatch(T event);
}
