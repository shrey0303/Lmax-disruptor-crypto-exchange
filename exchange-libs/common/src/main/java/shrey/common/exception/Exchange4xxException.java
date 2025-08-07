package shrey.common.exception;

/**
 * @author shrey
 * @since 2024
 */
public class Exchange4xxException extends ExchangeException {

    public Exchange4xxException(String message) {
        super(message);
    }

    public Exchange4xxException(String message, Throwable cause) {
        super(message, cause);
    }
}
