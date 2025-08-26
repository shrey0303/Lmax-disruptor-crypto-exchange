package shrey.exchange.offset;

/**
 * @author shrey
 * @since 2024
 */
public interface SnapshotRepository {
    Long getLastOffset();
    void persistLastOffset(long offset);
}
