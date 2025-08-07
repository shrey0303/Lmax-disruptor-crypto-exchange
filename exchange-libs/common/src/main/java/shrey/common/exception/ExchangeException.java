package shrey.common.exception;

/**
 * @author shrey
 * @since 2024
 */
public class ExchangeException extends RuntimeException {

    public ExchangeException(String message) {
        super(message);
    }

    public ExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
