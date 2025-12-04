package shrey.exchangeclient.cluster;

import java.util.concurrent.CompletableFuture;

/**
 * @author shrey
 * @since 2024
 */
public interface RequestBufferDispatcher<T> extends RequestBufferChannel {
    CompletableFuture<BaseResponse> dispatch(T request);
}
