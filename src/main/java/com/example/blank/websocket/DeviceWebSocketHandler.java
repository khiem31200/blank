package com.example.blank.websocket;

import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import com.example.blank.service.DeviceKeyService;
import com.example.blank.service.FaceFlowService;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Tang nghiep vu thiet bi tren base WebSocket. @Primary de WebSocketConfig chon handler nay
 * (base van la @Component nhung khong duoc dung truc tiep). Base khong bi sua.
 *
 * Luong 2: register (xac thuc key) + heartbeat + huy session khi dong.
 * Luong 3/4: enroll_image / recognize_image -> uy quyen cho FaceFlowService (state machine + goi Python).
 */
@Log4j2
@Component
@Primary
public class DeviceWebSocketHandler extends BaseWebSocketHandler {

    private static final String ATTR_DEVICE_ID = "deviceId";

    private final DeviceKeyService keyService;
    private final DeviceRepository deviceRepository;
    private final DeviceSessionRegistry registry;
    private final FaceFlowService faceFlow;

    public DeviceWebSocketHandler(ObjectMapper mapper,
                                  DeviceKeyService keyService,
                                  DeviceRepository deviceRepository,
                                  DeviceSessionRegistry registry,
                                  FaceFlowService faceFlow) {
        super(mapper);
        this.keyService = keyService;
        this.deviceRepository = deviceRepository;
        this.registry = registry;
        this.faceFlow = faceFlow;
    }

    @Override
    protected void handleBusinessMessage(WebSocketSession session, String type, JsonNode data) {
        switch (type) {
            case "register"        -> handleRegister(session, data);
            case "heartbeat"       -> handleHeartbeat(session);
            case "ackRotateKey"    -> handleRotateAck(session, data);
            case "enroll_image"    -> handleEnrollImage(session, data);     // Luong 3
            case "recognize_image" -> handleRecognizeImage(session, data);  // Luong 4
            default                -> super.handleBusinessMessage(session, type, data); // -> error unknown_type
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
        DeviceRuntime rt = registry.bind(deviceId, session);
        rt.setLastHeartbeatAt(Instant.now());
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
        DeviceRuntime rt = registry.get(deviceId);
        if (rt != null) rt.setLastHeartbeatAt(Instant.now());
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

    // ---- Luong 3: thiet bi gui anh enroll ----
    private void handleEnrollImage(WebSocketSession session, JsonNode data) {
        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) {
            sendJson(session, Map.of("type", "error", "reason", "not_registered"));
            return;
        }
        faceFlow.onEnrollImage(deviceId, text(data, "sessionId"), text(data, "image"));
    }

    // ---- Luong 4: thiet bi gui anh recognize (nut bam vat ly) ----
    private void handleRecognizeImage(WebSocketSession session, JsonNode data) {
        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) {
            sendJson(session, Map.of("type", "error", "reason", "not_registered"));
            return;
        }
        faceFlow.onRecognizeImage(deviceId, text(data, "image"));
    }

    // ---- Vong doi: don session + danh dau offline khi dong ----
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        super.afterConnectionClosed(session, status);
        String deviceId = (String) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) return;
        faceFlow.onDeviceDisconnected(deviceId); // huy phien enroll treo TRUOC khi xoa runtime

        // Khoang cach toi heartbeat gan nhat: giup phan biet "chet giua luc hoat dong" voi "chet luc idle"
        // ma khong can doi chieu timestamp tay qua nhieu dong log (xem debug rot WS ngay 2026-07-19).
        DeviceRuntime rt = registry.get(deviceId);
        String heartbeatGap = rt == null
                ? "?"
                : Duration.between(rt.getLastHeartbeatAt(), Instant.now()).toSeconds() + "s";

        registry.remove(deviceId);
        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setStatus("offline");
            d.setLastSeen(Instant.now());
            deviceRepository.save(d);
        });
        log.info("WS device offline: {} (online={}, heartbeat_gap={})", deviceId, registry.online(), heartbeatGap);
    }

    private static String text(JsonNode data, String field) {
        JsonNode n = data.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }
}
