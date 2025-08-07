package shrey.common.exception;

/**
 * @author shrey
 * @since 2024
 */
public class Exchange5xxException extends ExchangeException {
    public Exchange5xxException(String message) {
        super(message);
    }

    public Exchange5xxException(String message, Throwable cause) {
        super(message, cause);
    }
}
