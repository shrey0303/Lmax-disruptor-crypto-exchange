package shrey.exchange.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import shrey.exchange.domain.LatencyStats;
import shrey.exchange.domain.LatencyTracker;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LatencyWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final LatencyTracker latencyTracker;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::broadcastStats, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Client connected to latency WS: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Client disconnected from latency WS: {}", session.getId());
    }

    private void broadcastStats() {
        if (sessions.isEmpty()) return;
        
        try {
            LatencyStats stats = latencyTracker.getStats();
            String jsonFormat = objectMapper.writeValueAsString(stats);
            TextMessage message = new TextMessage(jsonFormat);
            
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (Exception e) {
                        log.warn("Failed to send latency to session {}", session.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error broadcasting latency stats", e);
        }
    }
}
