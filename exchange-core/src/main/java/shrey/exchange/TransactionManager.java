package shrey.exchange;

/**
 * @author shrey
 * @since 2024
 */
public interface TransactionManager {
    void doInNewTransaction(Runnable runnable);
}
