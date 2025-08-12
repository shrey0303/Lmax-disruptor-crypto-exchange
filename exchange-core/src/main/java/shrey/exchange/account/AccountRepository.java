package shrey.exchange.account;

import shrey.exchange.domain.TradingWallet;
import java.util.List;
import java.util.stream.Stream;

public interface AccountRepository {
    Stream<TradingWallet> wallets();
    Long lastedId();
    void persistWallets(List<TradingWallet> wallets);
    void persistLastId(Long id);
}
