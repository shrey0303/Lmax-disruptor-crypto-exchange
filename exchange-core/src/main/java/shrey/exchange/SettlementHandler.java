package shrey.exchange;

import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shrey.bank.proto.TradingProto;
import shrey.exchange.domain.CircuitBreaker;
import shrey.exchange.domain.LatencyTracker;
import shrey.exchange.domain.RiskEngine;
import shrey.exchange.domain.TradingWallets;
import shrey.exchange.account.BalanceResult;

@Slf4j
@RequiredArgsConstructor
public class SettlementHandler implements EventHandler<CommandBufferEvent> {

    private final TradingWallets wallets;
    private final RiskEngine riskEngine;
    private final CircuitBreaker circuitBreaker;
    private final LatencyTracker latencyTracker;

    @Override
    public void onEvent(CommandBufferEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event.isRejected()) {
            event.setResult(BalanceResult.of("Command Rejected", 400));
            return;
        }
        
        try {
            switch (event.getCommand().getCommandLog().getTypeCase()) {
                case CREATETRADINGACCOUNTCOMMAND -> {
                    long id = wallets.newTradingAccount();
                    event.setResult(BalanceResult.of("Create trading account success::" + id, 0));
                }
                case DEPOSITASSETCOMMAND -> {
                    var cmd = event.getCommand().getCommandLog().getDepositAssetCommand();
                    wallets.deposit(cmd.getAccountId(), cmd.getAsset(), cmd.getAmount());
                    event.setResult(BalanceResult.of("Deposit success", 0));
                }
                case WITHDRAWASSETCOMMAND -> {
                    var cmd = event.getCommand().getCommandLog().getWithdrawAssetCommand();
                    wallets.withdraw(cmd.getAccountId(), cmd.getAsset(), cmd.getAmount());
                    event.setResult(BalanceResult.of("Withdraw success", 0));
                }
                case PLACEORDERCOMMAND -> {
                    var result = event.getMatchingResult();
                    if (result != null && result.getTrades() != null) {
                        for (var trade : result.getTrades()) {
                            wallets.settleTrade(trade);
                            circuitBreaker.updatePrice(trade.getSymbol(), trade.getPrice(), trade.getTimestampNanos());
                        }
                    }
                    var order = event.getOrder();
                    if (order != null && (order.getStatus() == TradingProto.OrderStatus.CANCELLED || order.getStatus() == TradingProto.OrderStatus.REJECTED)) {
                        try {
                            riskEngine.releaseAssetsForCancel(order, wallets);
                        } catch(Exception e) {
                            log.warn("Failed to release held assets for cancelled order {}", order.getId(), e);
                        }
                    }
                    event.setResult(BalanceResult.of("Order placed success::" + (order != null ? order.getId() : ""), 0));
                }
                case CANCELORDERCOMMAND -> {
                    var order = event.getOrder();
                    if (order != null) {
                        try {
                            riskEngine.releaseAssetsForCancel(order, wallets);
                        } catch(Exception e) {
                            log.warn("Failed to release held assets for cancelled order {}", order.getId(), e);
                        }
                    }
                    event.setResult(BalanceResult.of("Cancel order success", 0));
                }
                default -> event.setResult(BalanceResult.of("Unknown command", 400));
            }
        } catch (Exception e) {
            log.error("Error in SettlementHandler", e);
            event.setResult(BalanceResult.of(e.getMessage(), 500));
        } finally {
            latencyTracker.recordLatencies(
                event.getEntryTimestampNanos(),
                event.getRiskFinishedNanos(),
                event.getMatchFinishedNanos(),
                System.nanoTime()
            );
        }
    }
}
