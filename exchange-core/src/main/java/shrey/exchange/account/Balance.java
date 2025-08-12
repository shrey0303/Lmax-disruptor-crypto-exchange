package shrey.exchange.account;

import shrey.common.exception.Exchange4xxException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author shrey
 * @since 2024
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Balance {
    private long id;
    private long amount;
    private int precision;
    private boolean active;

    public void increase(long amount) {
        this.amount += amount;
    }

    public void decrease(long amount) {
        if (this.amount < amount) {
            throw new Exchange4xxException("Insufficient balance");
        }
        this.amount -= amount;
    }

    public void active() {
        this.active = true;
    }

    public void inactive() {
        this.active = false;
    }
}
