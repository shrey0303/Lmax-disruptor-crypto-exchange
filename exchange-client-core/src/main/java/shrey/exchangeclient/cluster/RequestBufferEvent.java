package shrey.exchangeclient.cluster;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.concurrent.CompletableFuture;

/**
 * @author shrey
 * @since 2024
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RequestBufferEvent {
    private BaseRequest request;
    private CompletableFuture<BaseResponse> responseFuture;
}
