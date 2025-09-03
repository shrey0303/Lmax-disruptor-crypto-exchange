package shrey.exchange.infra.adapter;

import shrey.bank.proto.TradingProto;
import shrey.exchange.infra.SimpleReplier;
import shrey.exchange.ReplyBufferEvent;
import shrey.exchange.ReplyBufferHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Profile({"leader"})
@Component
@RequiredArgsConstructor
public class ReplyBufferHandlerAdapter implements ReplyBufferHandler {

    private final SimpleReplier simpleReplier;

    @Override
    public void onEvent(ReplyBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        Optional.ofNullable(simpleReplier.repliers.get(event.getReplyChannel()))
                .ifPresent(streamObserver -> streamObserver.onNext(
                        TradingProto.TradingResponse.newBuilder()
                            .setBaseResult(
                                TradingProto.TradingBaseResult.newBuilder()
                                    .setCorrelationId(event.getCorrelationId())
                                    .setMessage(event.getResult().toString())
                                    .build()
                            )
                            .build()
                ));
    }
}
