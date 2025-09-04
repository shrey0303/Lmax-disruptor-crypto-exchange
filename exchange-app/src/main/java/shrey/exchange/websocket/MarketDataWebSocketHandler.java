package shrey.exchange.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import shrey.exchange.domain.OrderBookManager;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final OrderBookManager orderBookManager;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::broadcastMarketData, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Client connected to market data WS: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Client disconnected from market data WS: {}", session.getId());
    }

    private void broadcastMarketData() {
        if (sessions.isEmpty()) return;
        
        try {
            var book = orderBookManager.getOrderBook("BTC_USDT");
            if (book != null) {
                // Not thread safe to read book directly, but acceptable for demo 1s snapshot
                Map<String, Object> snapshot = new HashMap<>();
                snapshot.put("symbol", "BTC_USDT");
                snapshot.put("bestBid", book.getBestBidPrice());
                snapshot.put("bestAsk", book.getBestAskPrice());
                
                String jsonFormat = objectMapper.writeValueAsString(snapshot);
                TextMessage message = new TextMessage(jsonFormat);
                
                for (WebSocketSession session : sessions) {
                    if (session.isOpen()) {
                        try {
                            session.sendMessage(message);
                        } catch (Exception e) {
                            log.warn("Failed to send market data to session {}", session.getId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error broadcasting market data", e);
        }
    }
}
