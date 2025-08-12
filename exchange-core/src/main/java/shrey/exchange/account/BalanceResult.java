package shrey.exchange.account;

import shrey.exchange.BaseResult;
import lombok.*;

/**
 * @author shrey
 * @since 2024
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
@EqualsAndHashCode(callSuper = false)
public class BalanceResult extends BaseResult {
    private String message;
    private int code;

    @Override
    public String toString() {
        return String.format("%s::%s", code, message);
    }
}
