package shrey.exchangeadmin.transport.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import shrey.exchangeadmin.common.query.OrderBookQueryCommand;
import shrey.exchangeadmin.common.response.MarketDataResponse;
import shrey.exchangeclient.cluster.BaseRequest;
import shrey.exchangeclient.cluster.BaseResponse;
import shrey.exchangeclient.cluster.RequestBufferDispatcher;

@RestController
@RequestMapping("/api/v1/marketdata")
@RequiredArgsConstructor
@Slf4j
public class MarketDataQueryResource {

    private final RequestBufferDispatcher<BaseRequest> requestBufferDispatcher;

    @GetMapping("/orderbook/{symbol}")
    public ResponseEntity<BaseResponse> getOrderBook(@PathVariable String symbol, @RequestParam(defaultValue = "10") int depth) {
        log.info("Received orderbook query for symbol: {}", symbol);
        OrderBookQueryCommand query = new OrderBookQueryCommand();
        query.setSymbol(symbol);
        query.setDepth(depth);
        
        var response = requestBufferDispatcher.dispatch(query).join();
        return ResponseEntity.status(response.getCode() == 200 ? 200 : 400).body(response);
    }
}
