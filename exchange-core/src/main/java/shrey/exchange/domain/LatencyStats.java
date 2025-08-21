package shrey.exchange.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LatencyStats {

    private Percentiles e2e;
    private Percentiles risk;
    private Percentiles match;
    private Percentiles settle;

    @Data
    @AllArgsConstructor
    public static class Percentiles {
        private double p50; // in microseconds
        private double p95;
        private double p99;
        private double p999;
    }
}
