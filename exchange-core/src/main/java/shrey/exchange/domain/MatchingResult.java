package shrey.exchange.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingResult {
    private Order order;
    @Builder.Default
    private List<Trade> trades = new ArrayList<>();
    private boolean orderBookChanged;

    public void addTrade(Trade trade) {
        if (trades == null) {
            trades = new ArrayList<>();
        }
        trades.add(trade);
    }
}
