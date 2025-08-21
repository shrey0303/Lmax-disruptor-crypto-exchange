package shrey.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import shrey.bank.proto.TradingProto;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class BaseCommand {
    private TradingProto.TradingCommandLog commandLog;
}
