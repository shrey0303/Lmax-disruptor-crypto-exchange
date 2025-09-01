package shrey.exchange.cluster;

import lombok.Data;

/**
 * @author shrey
 * @since 2024
 */
@Data
public class FollowerProperties {
    private int bufferSize = 1 << 10;
    private int pollingInterval = 100;
}
