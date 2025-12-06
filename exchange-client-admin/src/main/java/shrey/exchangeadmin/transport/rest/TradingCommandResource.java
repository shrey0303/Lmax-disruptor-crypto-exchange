package shrey.exchangeadmin.transport.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import shrey.exchangeadmin.common.command.CancelOrderCommand;
import shrey.exchangeadmin.common.command.PlaceOrderCommand;
import shrey.exchangeclient.cluster.BaseRequest;
import shrey.exchangeclient.cluster.BaseResponse;
import shrey.exchangeclient.cluster.RequestBufferDispatcher;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class TradingCommandResource {

    private final RequestBufferDispatcher<BaseRequest> requestBufferDispatcher;

    @PostMapping("/place")
    public ResponseEntity<BaseResponse> placeOrder(@RequestBody PlaceOrderCommand command) {
        log.info("Received place order command: {}", command);
        var response = requestBufferDispatcher.dispatch(command).join();
        return ResponseEntity.status(response.getCode() == 200 ? 200 : 400).body(response);
    }

    @PostMapping("/cancel")
    public ResponseEntity<BaseResponse> cancelOrder(@RequestBody CancelOrderCommand command) {
        log.info("Received cancel order command: {}", command);
        var response = requestBufferDispatcher.dispatch(command).join();
        return ResponseEntity.status(response.getCode() == 200 ? 200 : 400).body(response);
    }
}
