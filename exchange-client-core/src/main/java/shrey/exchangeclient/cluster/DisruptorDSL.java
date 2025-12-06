package shrey.exchangeclient.cluster;

import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;

/**
 * @author shrey
 * @since 2024
 */
public interface DisruptorDSL<T> {
    Disruptor<T> build(int bufferSize, WaitStrategy waitStrategy);
}
