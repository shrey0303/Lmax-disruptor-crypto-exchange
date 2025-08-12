package shrey.exchange.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import shrey.common.exception.Exchange4xxException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingWallet {
    private long accountId;
    
    @Builder.Default
    private Map<String, Long> balances = new ConcurrentHashMap<>();
    
    @Builder.Default
    private Map<String, Long> holds = new ConcurrentHashMap<>();

    public void deposit(String asset, long amount) {
        if (amount <= 0) {
            throw new Exchange4xxException("Deposit amount must be greater than 0");
        }
        balances.put(asset, balances.getOrDefault(asset, 0L) + amount);
    }

    public void withdraw(String asset, long amount) {
        if (amount <= 0) {
            throw new Exchange4xxException("Withdraw amount must be greater than 0");
        }
        long available = getAvailableBalance(asset);
        if (available < amount) {
            throw new Exchange4xxException(String.format("Insufficient available %s balance. Available: %d, Requested: %d", asset, available, amount));
        }
        balances.put(asset, balances.get(asset) - amount);
    }

    public void hold(String asset, long amount) {
        if (amount <= 0) return;
        long available = getAvailableBalance(asset);
        if (available < amount) {
            throw new Exchange4xxException(String.format("Insufficient %s balance to hold. Available: %d, Requested: %d", asset, available, amount));
        }
        holds.put(asset, holds.getOrDefault(asset, 0L) + amount);
    }

    public void release(String asset, long amount) {
        if (amount <= 0) return;
        long currentHold = holds.getOrDefault(asset, 0L);
        if (currentHold < amount) {
            throw new Exchange4xxException(String.format("Release amount %d exceeds current hold %d for %s", amount, currentHold, asset));
        }
        holds.put(asset, currentHold - amount);
        if (holds.get(asset) == 0L) {
            holds.remove(asset);
        }
    }

    public void deductHold(String asset, long amount) {
        if (amount <= 0) return;
        
        long currentHold = holds.getOrDefault(asset, 0L);
        if (currentHold < amount) {
            throw new Exchange4xxException(String.format("Cannot deduct %d from hold, only %d held for %s", amount, currentHold, asset));
        }
        
        holds.put(asset, currentHold - amount);
        if (holds.get(asset) == 0L) {
            holds.remove(asset);
        }
        
        long currentBalance = balances.getOrDefault(asset, 0L);
        balances.put(asset, currentBalance - amount);
    }

    public long getAvailableBalance(String asset) {
        return balances.getOrDefault(asset, 0L) - holds.getOrDefault(asset, 0L);
    }

    public long getHold(String asset) {
        return holds.getOrDefault(asset, 0L);
    }
}
