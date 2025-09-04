package shrey.exchange.transport.rest;

import shrey.exchange.transport.BaseErrorResult;
import shrey.common.exception.Exchange4xxException;
import shrey.common.exception.Exchange5xxException;
import shrey.common.exception.ExchangeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @author shrey
 * @since 2024
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(Exchange4xxException.class)
    public ResponseEntity<BaseErrorResult> handle4xxException(Exchange4xxException exception) {
        log.error("4xx Exception", exception);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST.value())
                .body(new BaseErrorResult(exception.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(Exchange5xxException.class)
    public ResponseEntity<BaseErrorResult> handle5xxException(Exchange5xxException exception) {
        log.error("5xx Exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .body(new BaseErrorResult(exception.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    @ExceptionHandler(ExchangeException.class)
    public ResponseEntity<BaseErrorResult> handleExchangeException(Exchange5xxException exception) {
        log.error("Unknown exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .body(new BaseErrorResult(exception.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

}
