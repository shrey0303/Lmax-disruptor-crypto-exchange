package shrey.exchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class CommandHandlerImpl implements CommandHandler {

    private final RiskValidationHandler riskHandler;
    private final MatchingHandler matchingHandler;
    private final SettlementHandler settlementHandler;

    @Override
    public BaseResult onCommand(BaseCommand command) {
        CommandBufferEvent event = new CommandBufferEvent(null, null, command);
        try {
            riskHandler.onEvent(event, 0, false);
            matchingHandler.onEvent(event, 0, false);
            settlementHandler.onEvent(event, 0, false);
        } catch (Exception e) {
            log.error("Replay execution failed", e);
        }
        return event.getResult();
    }
}
