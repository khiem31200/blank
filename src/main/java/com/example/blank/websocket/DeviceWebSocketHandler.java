package com.example.blank.websocket;

import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import com.example.blank.service.DeviceKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Tang nghiep vu thiet bi tren base WebSocket. @Primary de WebSocketConfig chon handler nay
 * (base van la @Component nhung khong duoc dung truc tiep). Base khong bi sua.
 *
 * Luong 2: register (xac thuc key) + heartbeat + huy session khi dong.
 * Cac type khac (enroll_image / recognize_image) se cam vao day o Luong 3/4.
 */
@Component
@Primary
public class DeviceWebSocketHandler extends BaseWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);
    private static final String ATTR_DEVICE_ID = "deviceId";

    private final DeviceKeyService keyService;
    private final DeviceRepository deviceRepository;
    private final DeviceSessionRegistry registry;

    public DeviceWebSocketHandler(ObjectMapper mapper,
                                  DeviceKeyService keyService,
                                  DeviceRepository deviceRepository,
                                  DeviceSessionRegistry registry) {
        super(mapper);
        this.keyService = keyService;
        this.deviceRepository = deviceRepository;
        this.registry = registry;
    }

    @Override
    protected void handleBusinessMessage(WebSocketSession session, String type, JsonNode data) {
        switch (type) {
            case "register"     -> handleRegister(session, data);
            case "heartbeat"    -> handleHeartbeat(session);
            case "ackRotateKey" -> handleRotateAck(session, data);
            default             -> super.handleBusinessMessage(session, type, data); // -> error unknown_type
        }
    }

    /** Gui payload toi thiet bi theo deviceId (dung boi scheduler xoay key). true neu gui duoc. */
    public boolean sendToDevice(String deviceId, Object payload) {
        WebSocketSession s = registry.session(deviceId);
        if (s == null || !s.isOpen()) return false;
        sendJson(s, payload);
        return true;
    }

    // ---- Luong 2: register ----
    private void handleRegister(WebSocketSession session, JsonNode data) {
        String deviceId = text(data, "deviceId");
        String apiKey   = text(data, "apiKey");
        if (deviceId == null || apiKey == null) {
            sendJson(session, Map.of("type", "register_ack", "status", "fail", "reason", "missing_credentials"));
            return;
        }

        Device d = deviceRepository.findById(deviceId).orElse(null);
        if (d == null || !keyService.authenticate(d, apiKey)) {
            log.warn("WS register that bai: deviceId={}", deviceId);
            sendJson(session, Map.of("type", "register_ack", "status", "fail", "deviceId", deviceId));
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) {}
            return;
        }

        session.getAttributes().put(ATTR_DEVICE_ID, deviceId);
        registry.bind(deviceId, session);
        d.setStatus("online");
        d.setLastSeen(Instant.now());
        deviceRepository.save(d);

        log.info("WS register OK: deviceId={} (online={})", deviceId, registry.online());
        sendJson(session, Map.of("type", "register_ack", "status", "success", "deviceId", deviceId));
    }

    // ---- Luong 2: heartbeat ----
    private void handleHeartbeat(WebSocketSession session) {
        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) {
            sendJson(session, Map.of("type", "error", "reason", "not_registered"));
            return;
        }
        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setStatus("online");
            d.setLastSeen(Instant.now());
            deviceRepository.save(d);
        });
    }

    // ---- Luong 5: xac nhan da doi key -> promote pending ----
    private void handleRotateAck(WebSocketSession session, JsonNode data) {
        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) {
            sendJson(session, Map.of("type", "error", "reason", "not_registered"));
            return;
        }
        String status = text(data, "status");
        if (!"success".equals(status)) {
            log.warn("ackRotateKey status={} cho {} - khong promote", status, deviceId);
            return;
        }
        deviceRepository.findById(deviceId).ifPresent(d -> {
            keyService.promote(d);   // pending -> current (no-op neu khong co pending)
            log.info("ackRotateKey: da promote key cho {}", deviceId);
        });
    }

    // ---- Vong doi: don session + danh dau offline khi dong ----
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        super.afterConnectionClosed(session, status);
        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) return;
        registry.remove(deviceId);
        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setStatus("offline");
            d.setLastSeen(Instant.now());
            deviceRepository.save(d);
        });
        log.info("WS device offline: {} (online={})", deviceId, registry.online());
    }

    private static String text(JsonNode data, String field) {
        JsonNode n = data.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }
}
