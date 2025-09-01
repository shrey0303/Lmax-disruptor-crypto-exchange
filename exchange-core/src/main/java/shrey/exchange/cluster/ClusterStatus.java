package shrey.exchange.cluster;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author shrey
 * @since 2024
 */
public enum ClusterStatus {
    NOT_AVAILABLE,
    ACTIVE;

    public static final AtomicReference<ClusterStatus> STATE = new AtomicReference<>(NOT_AVAILABLE);
}
