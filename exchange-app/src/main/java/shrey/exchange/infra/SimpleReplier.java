package shrey.exchange.infra;

import shrey.bank.proto.TradingProto;
import io.grpc.stub.StreamObserver;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author shrey
 * @since 2024
 */
@Profile("leader")
@Component
public class SimpleReplier {
    public final Map<String, StreamObserver<TradingProto.TradingResponse>> repliers = new ConcurrentHashMap<>();
}
