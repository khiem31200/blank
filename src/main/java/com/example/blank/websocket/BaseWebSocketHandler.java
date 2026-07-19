package com.example.blank.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Component
public class BaseWebSocketHandler extends TextWebSocketHandler {

    // Ping giu nhip o tang WS protocol: phat hien session "chet" (khong con TCP song) som hon
    // thay vi cho tranport error tu phat hien (co the tre neu ha tang trung gian khong bao loi ngay).
    private static final long PING_INTERVAL_MS = 20_000;
    private static final long PONG_TIMEOUT_MS  = 45_000; // ~2 chu ky ping, cho phep truot 1 nhip

    // Registry phiên theo sessionId (tầng base: chưa gắn deviceId — đó là việc của tầng nghiệp vụ).
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> connectedAt = new ConcurrentHashMap<>(); // -> log thoi luong song
    private final Map<String, Instant> lastPongAt  = new ConcurrentHashMap<>(); // -> phat hien ping timeout
    private final ObjectMapper mapper;

    public BaseWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- Vòng đời ----
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Instant now = Instant.now();
        sessions.put(session.getId(), session);
        connectedAt.put(session.getId(), now);
        lastPongAt.put(session.getId(), now); // coi luc connect la 1 pong -> khong bi timeout oan ngay
        log.info("WS mở: {} (tổng {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        Instant start = connectedAt.remove(session.getId());
        lastPongAt.remove(session.getId());
        String lived = start == null ? "?" : Duration.between(start, Instant.now()).toSeconds() + "s";
        log.info("WS đóng: {} ({}) (còn {}, sống {})", session.getId(), status, sessions.size(), lived);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("WS lỗi transport: {}", session.getId(), ex);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        lastPongAt.put(session.getId(), Instant.now());
    }

    /**
     * Quet dinh ky: gui ping cho tung session dang mo; neu qua PONG_TIMEOUT_MS khong thay pong
     * (ke ca ping thuong cua browser/thiet bi tu tra loi o tang protocol) thi coi la chet, chu dong dong
     * thay vi cho TCP tu bao loi (co the tre hoac khong bao gio bao qua NAT/proxy trung gian).
     */
    @Scheduled(fixedRate = PING_INTERVAL_MS)
    public void pingSessions() {
        sessions.forEach((id, session) -> {
            if (!session.isOpen()) return;
            Instant lastPong = lastPongAt.get(id);
            if (lastPong != null && Duration.between(lastPong, Instant.now()).toMillis() > PONG_TIMEOUT_MS) {
                log.warn("WS khong pong qua {}ms, chu dong dong: {}", PONG_TIMEOUT_MS, id);
                try { session.close(new CloseStatus(4000, "ping_timeout")); } catch (IOException ignored) {}
                return;
            }
            try {
                sendPing(session);
            } catch (IOException e) {
                log.warn("Gui ping that bai toi {}: {}", id, e.getMessage());
            }
        });
    }

    private synchronized void sendPing(WebSocketSession session) throws IOException {
        if (session.isOpen()) session.sendMessage(new PingMessage());
    }

    // ---- Nhận bản tin ----
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode data;
        try {
            data = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("WS payload không phải JSON hợp lệ từ {}", session.getId());
            sendJson(session, error("invalid_json"));
            return;
        }
        String type = data.path("type").asText("");
        switch (type) {
            case "ping" -> {                    // để test kết nối / giữ nhịp
                ObjectNode pong = mapper.createObjectNode();
                pong.put("type", "pong");
                pong.put("ts", System.currentTimeMillis());
                sendJson(session, pong);
            }
            case "echo" -> {                    // dội lại nguyên payload, tiện debug
                ObjectNode echo = mapper.createObjectNode();
                echo.put("type", "echo");
                echo.set("data", data);
                sendJson(session, echo);
            }
            default -> handleBusinessMessage(session, type, data); // <-- ĐIỂM MỞ RỘNG
        }
    }

    /**
     * Điểm cắm cho tầng nghiệp vụ (register/enroll/recognize/rotate_key...).
     * Base để trống — override hoặc mở rộng ở đây khi thêm luồng thật.
     */
    protected void handleBusinessMessage(WebSocketSession session, String type, JsonNode data) {
        log.debug("Chưa xử lý type='{}' từ {}", type, session.getId());
        sendJson(session, error("unknown_type:" + type));
    }

    // ---- Tiện ích gửi (dùng lại ở tầng nghiệp vụ) ----
    public synchronized void sendJson(WebSocketSession session, Object payload) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.error("Gửi WS thất bại tới {}", session.getId(), e);
        }
    }

    public void sendToSession(String sessionId, Object payload) {
        sendJson(sessions.get(sessionId), payload);
    }

    public void broadcast(Object payload) {
        sessions.values().forEach(s -> sendJson(s, payload));
    }

    public int count() { return sessions.size(); }

    private ObjectNode error(String reason) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "error");
        n.put("reason", reason);
        return n;
    }
}
