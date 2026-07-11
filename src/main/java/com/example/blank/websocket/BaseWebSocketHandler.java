package com.example.blank.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Component
public class BaseWebSocketHandler extends TextWebSocketHandler {

    // Registry phiên theo sessionId (tầng base: chưa gắn deviceId — đó là việc của tầng nghiệp vụ).
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public BaseWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- Vòng đời ----
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WS mở: {} (tổng {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WS đóng: {} ({}) (còn {})", session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("WS lỗi transport: {}", session.getId(), ex);
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
