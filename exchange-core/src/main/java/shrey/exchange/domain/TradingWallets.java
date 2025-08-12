package shrey.exchange.domain;

import lombok.Getter;
import lombok.Setter;
import shrey.common.exception.Exchange4xxException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TradingWallets {
    private final ConcurrentHashMap<Long, TradingWallet> wallets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TradingWallet> changedWallets = new ConcurrentHashMap<>();

    private final AtomicLong lastedId = new AtomicLong(0);

    @Getter
    @Setter
    private boolean enableChangedCapture = false;

    public long getLastedId() {
        return lastedId.get();
    }

    public void setLastedId(long id) {
        this.lastedId.set(id);
    }

    public List<TradingWallet> getChangedWallets() {
        return changedWallets.values().stream().toList();
    }

    public void clearChangedWallets() {
        changedWallets.clear();
    }

    public long newTradingAccount() {
        long id = lastedId.incrementAndGet();
        wallets.put(id, new TradingWallet(id, new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
        captureWallet(id);
        return id;
    }

    public void deposit(long accountId, String asset, long amount) {
        Optional.ofNullable(wallets.get(accountId))
            .orElseThrow(() -> new Exchange4xxException(String.format("Account %s not found", accountId)))
            .deposit(asset, amount);
        captureWallet(accountId);
    }

    public void withdraw(long accountId, String asset, long amount) {
        Optional.ofNullable(wallets.get(accountId))
            .orElseThrow(() -> new Exchange4xxException(String.format("Account %s not found", accountId)))
            .withdraw(asset, amount);
        captureWallet(accountId);
    }

    public void hold(long accountId, String asset, long amount) {
        Optional.ofNullable(wallets.get(accountId))
            .orElseThrow(() -> new Exchange4xxException(String.format("Account %s not found", accountId)))
            .hold(asset, amount);
        captureWallet(accountId);
    }

    public void release(long accountId, String asset, long amount) {
        TradingWallet wallet = wallets.get(accountId);
        if (wallet != null) {
            wallet.release(asset, amount);
            captureWallet(accountId);
        }
    }

    public void settleTrade(Trade trade) {
        int sep = trade.getSymbol().indexOf('_');
        String baseAsset = trade.getSymbol().substring(0, sep);
        String quoteAsset = trade.getSymbol().substring(sep + 1);
        
        long baseAmount = trade.getQuantity();
        long quoteAmount;
        try {
            quoteAmount = Math.multiplyExact(trade.getQuantity(), trade.getPrice());
        } catch (ArithmeticException e) {
            throw new Exchange4xxException("Settlement overflow: quantity * price exceeds system limits");
        }

        // Determine buyer/seller from taker side
        long buyerAccountId, sellerAccountId;
        if (trade.getTakerSide() == shrey.bank.proto.TradingProto.OrderSide.BUY) {
            buyerAccountId = trade.getTakerAccountId();
            sellerAccountId = trade.getMakerAccountId();
        } else {
            buyerAccountId = trade.getMakerAccountId();
            sellerAccountId = trade.getTakerAccountId();
        }

        TradingWallet buyerWallet = wallets.get(buyerAccountId);
        TradingWallet sellerWallet = wallets.get(sellerAccountId);

        if (buyerWallet == null || sellerWallet == null) {
            throw new Exchange4xxException("Invalid account ID in trade execution");
        }

        // Release excess hold for BUY taker getting price improvement.
        // Hold was placed at takerPrice, match executed at trade.price (which is <= takerPrice).
        // The difference is excess hold that must be released to prevent balance drift.
        if (trade.getTakerSide() == shrey.bank.proto.TradingProto.OrderSide.BUY
                && trade.getTakerPrice() > trade.getPrice()) {
            long excessHold = (trade.getTakerPrice() - trade.getPrice()) * trade.getQuantity();
            if (excessHold > 0) {
                buyerWallet.release(quoteAsset, excessHold);
            }
        }

        // Buyer: deduct quote hold at match price, receive base
        buyerWallet.deductHold(quoteAsset, quoteAmount);
        buyerWallet.deposit(baseAsset, baseAmount);

        // Seller: deduct base hold, receive quote
        sellerWallet.deductHold(baseAsset, baseAmount);
        sellerWallet.deposit(quoteAsset, quoteAmount);

        captureWallet(buyerAccountId);
        captureWallet(sellerAccountId);
    }

    public void putWallet(TradingWallet wallet) {
        wallets.put(wallet.getAccountId(), wallet);
    }

    public TradingWallet getWallet(long accountId) {
        return wallets.get(accountId);
    }

    private void captureWallet(long accountId) {
        if (enableChangedCapture) {
            var wallet = wallets.get(accountId);
            TradingWallet copy = new TradingWallet(
                wallet.getAccountId(),
                new ConcurrentHashMap<>(wallet.getBalances()),
                new ConcurrentHashMap<>(wallet.getHolds())
            );
            changedWallets.put(accountId, copy);
        }
    }
}
