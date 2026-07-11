package com.example.blank.service;

import com.example.blank.websocket.DeviceRuntime;
import com.example.blank.websocket.DeviceSessionRegistry;
import com.example.blank.websocket.DeviceState;
import com.example.blank.websocket.EnrollSession;
import com.example.blank.websocket.EnrollSession.Outcome;
import com.example.blank.websocket.EnrollSession.Status;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrator Luong 3 (enroll) + Luong 4 (recognize) + state machine loai tru.
 *
 * Quy tac (skill v2):
 *  - IDLE + enroll start        -> ENROLLING
 *  - IDLE + recognize_image     -> RECOGNIZING
 *  - RECOGNIZING + enroll start -> tu choi "device busy" (C)
 *  - ENROLLING (dang gom anh) + recognize_image -> preempt: huy enroll, RECOGNIZING (F)
 *  - ENROLLING (da du anh, dang goi backend)    -> khong preempt nua, tra busy
 *  - RECOGNIZING + recognize_image moi          -> tra busy (chong spam nut)
 *  - Cong uu tien toan cuc: enroll cho activeRecognitions == 0 (co cap) truoc khi goi Python.
 *
 * Moi chuyen state deu nam trong DeviceRuntime.lock; TUYET DOI khong goi backend trong lock.
 */
@Log4j2
@Service
public class FaceFlowService {

    private final DeviceSessionRegistry registry;
    private final RecognitionClient client;
    private final ObjectMapper mapper;

    private final int imageCount;
    private final Duration enrollTimeout;      // thoi gian toi da GOM anh
    private final Duration backendGrace;       // cho them khi da du anh va dang goi backend
    private final Duration priorityWait;       // cap cho enroll cho recognize xong
    private final int maxImageChars;           // chan anh base64 qua lon (WS buffer 512KB)

    /** Cong uu tien toan cuc: so recognize dang chay tren backend. */
    private final AtomicInteger activeRecognitions = new AtomicInteger();

    public FaceFlowService(DeviceSessionRegistry registry,
                           RecognitionClient client,
                           ObjectMapper mapper,
                           @Value("${enroll.image-count:5}") int imageCount,
                           @Value("${enroll.timeout:PT30S}") Duration enrollTimeout,
                           @Value("${recognition.enroll-timeout:PT20S}") Duration backendGrace,
                           @Value("${enroll.priority-wait:PT10S}") Duration priorityWait,
                           @Value("${device.image.max-base64-length:400000}") int maxImageChars) {
        this.registry = registry;
        this.client = client;
        this.mapper = mapper;
        this.imageCount = imageCount;
        this.enrollTimeout = enrollTimeout;
        this.backendGrace = backendGrace;
        this.priorityWait = priorityWait;
        this.maxImageChars = maxImageChars;
    }

    /** Ket qua tra cho controller: HTTP status + body. */
    public record EnrollStartResult(int http, Map<String, Object> body) {}

    // ================= LUONG 3: web bam enroll =================

    /** Chan cho toi khi enroll xong / timeout / bi huy. Goi tu thread request cua Spring MVC. */
    public EnrollStartResult startEnroll(String deviceId, String name) {
        if (isBlank(deviceId) || isBlank(name)) {
            return err(400, "missing deviceId or name");
        }
        DeviceRuntime rt = registry.get(deviceId);
        if (rt == null || rt.getSession() == null || !rt.getSession().isOpen()) {
            return err(400, "device offline");
        }

        // Fix #1: check trung ten TRUOC khi cham thiet bi -> khong phi cong chup, khong tao identity mo coi
        try {
            if (client.nameExists(name)) return err(409, "name already exists");
        } catch (RecognitionClient.BackendException be) {
            return err(502, "backend unreachable: " + be.getMessage());
        }

        EnrollSession es;
        synchronized (rt.getLock()) {
            switch (rt.getState()) {
                case RECOGNIZING -> { return err(409, "device busy"); }          // quyet dinh C
                case ENROLLING   -> { return err(409, "already enrolling"); }
                case IDLE        -> { /* di tiep */ }
            }
            es = new EnrollSession(UUID.randomUUID().toString(), deviceId, name.trim(), imageCount);
            rt.setState(DeviceState.ENROLLING);
            rt.setEnrollSession(es);
        }

        if (!send(rt.getSession(), Map.of(
                "type", "start_enroll", "sessionId", es.getSessionId(), "count", imageCount))) {
            cleanupSession(rt, es);
            return err(400, "device offline");
        }
        log.info("Enroll bat dau: device={} session={} name={}", deviceId, es.getSessionId(), name);

        Outcome out = awaitOutcome(rt, es);
        return switch (out.status()) {
            case OK -> new EnrollStartResult(200, Map.of("status", "ok", "id", out.id(), "name", es.getName()));
            case TIMEOUT             -> new EnrollStartResult(200, Map.of("status", "timeout"));
            case INTERRUPTED         -> new EnrollStartResult(200, Map.of("status", "interrupted"));
            case DEVICE_DISCONNECTED -> new EnrollStartResult(200, Map.of("status", "device_disconnected"));
            case BACKEND_ERROR       -> new EnrollStartResult(502, Map.of("status", "error",
                    "message", out.message() == null ? "backend error" : out.message()));
        };
    }

    /** Cho ket qua phien enroll; xu ly timeout gom anh + gia han neu backend dang chay. */
    private Outcome awaitOutcome(DeviceRuntime rt, EnrollSession es) {
        CompletableFuture<Outcome> f = es.getFuture();
        try {
            return f.get(enrollTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            boolean processing;
            synchronized (rt.getLock()) {
                processing = es.isProcessing();
                if (!processing) cleanupSessionLocked(rt, es);
            }
            if (processing) {
                // Anh da du, backend dang xu ly -> cho not (co cap) thay vi bao timeout oan
                try {
                    return f.get(backendGrace.toMillis() + priorityWait.toMillis() + 2000, TimeUnit.MILLISECONDS);
                } catch (Exception e2) {
                    if (e2 instanceof InterruptedException) Thread.currentThread().interrupt();
                    return Outcome.backendError("backend qua cham: " + e2.getMessage());
                }
            }
            // Fix #2b: bao thiet bi dung chup phien da chet
            send(rt.getSession(), Map.of("type", "stop_enroll", "sessionId", es.getSessionId()));
            log.warn("Enroll timeout: device={} session={}", es.getDeviceId(), es.getSessionId());
            return Outcome.of(Status.TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cleanupSession(rt, es);
            return Outcome.backendError("interrupted");
        } catch (ExecutionException ee) {
            cleanupSession(rt, es);
            return Outcome.backendError(ee.getMessage());
        }
    }

    // ================= LUONG 3: thiet bi gui anh enroll =================

    /** Xu ly enroll_image tu WS. Goi tren thread WS cua thiet bi. */
    public void onEnrollImage(String deviceId, String sessionId, String image) {
        DeviceRuntime rt = registry.get(deviceId);
        if (rt == null) return;

        List<String> batch = null;
        EnrollSession es;
        synchronized (rt.getLock()) {
            es = rt.getEnrollSession();
            // Fix #2a: bo qua im lang anh cua phien da chet / sessionId khong khop
            if (es == null || !es.getSessionId().equals(sessionId) || es.isProcessing()) {
                log.debug("Bo qua enroll_image lac phien: device={} sessionId={}", deviceId, sessionId);
                return;
            }
            if (isBlank(image) || image.length() > maxImageChars) {
                log.warn("enroll_image khong hop le (rong hoac >{} chars) tu {}", maxImageChars, deviceId);
                return;
            }
            es.addImage(image);
            log.info("Enroll nhan anh {}/{}: device={} session={}",
                    es.imageCount(), es.getExpected(), deviceId, es.getSessionId());
            if (es.imageCount() >= es.getExpected()) {
                es.setProcessing(true);           // tu day khong preempt/huy duoc nua
                batch = es.snapshotImages();
            }
        }
        if (batch == null) return;

        // Cong uu tien: recognize dang chay thi enroll phai cho (cap priorityWait de khong ket vinh vien)
        waitForRecognitionsToDrain();

        Outcome out;
        try {
            RecognitionClient.EnrollResponse res = client.enroll(batch, es.getName());
            out = res.id() >= 0 ? Outcome.ok(res.id()) : Outcome.backendError(res.message());
            log.info("Enroll xong: device={} id={} name={}", deviceId, res.id(), es.getName());
        } catch (RecognitionClient.BackendException be) {
            out = Outcome.backendError(be.getMessage());
            log.warn("Enroll goi backend loi: device={} - {}", deviceId, be.getMessage());
        }

        cleanupSession(rt, es);
        es.getFuture().complete(out);
    }

    // ================= LUONG 4: thiet bi gui anh recognize =================

    /** Xu ly recognize_image tu WS (nut bam vat ly tren ESP32). Goi tren thread WS cua thiet bi. */
    public void onRecognizeImage(String deviceId, String image) {
        DeviceRuntime rt = registry.get(deviceId);
        if (rt == null) return;
        WebSocketSession ws = rt.getSession();

        if (isBlank(image) || image.length() > maxImageChars) {
            send(ws, Map.of("type", "recognize_result", "status", "error", "reason", "invalid_image"));
            return;
        }

        synchronized (rt.getLock()) {
            switch (rt.getState()) {
                case RECOGNIZING -> {                                     // Fix #4: chong spam nut
                    send(ws, Map.of("type", "busy", "reason", "recognizing"));
                    return;
                }
                case ENROLLING -> {
                    EnrollSession es = rt.getEnrollSession();
                    if (es != null && !es.isProcessing()) {               // preempt F: huy enroll dang gom
                        rt.setEnrollSession(null);
                        send(ws, Map.of("type", "stop_enroll", "sessionId", es.getSessionId())); // Fix #2b
                        es.getFuture().complete(Outcome.of(Status.INTERRUPTED));
                        rt.setState(DeviceState.RECOGNIZING);
                        log.info("Recognize preempt enroll: device={} session={}", deviceId, es.getSessionId());
                    } else {                                              // du anh roi, backend dang chay
                        send(ws, Map.of("type", "busy", "reason", "enrolling"));
                        return;
                    }
                }
                case IDLE -> rt.setState(DeviceState.RECOGNIZING);
            }
        }

        activeRecognitions.incrementAndGet();
        try {
            RecognitionClient.RecognizeResponse res = client.recognize(image);
            boolean matched = res.identity() != null && !res.identity().isBlank()
                    && !"unknown".equalsIgnoreCase(res.identity());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "recognize_result");
            msg.put("status", matched ? "ok" : "unknown");
            if (matched) msg.put("identity", res.identity());
            if (res.confidence() != null) msg.put("confidence", res.confidence());
            send(ws, msg);
            log.info("Recognize xong: device={} status={} identity={}",
                    deviceId, matched ? "ok" : "unknown", res.identity());
        } catch (RecognitionClient.BackendException be) {
            // Loi backend -> van tra ket qua de ESP32 ket thuc luong, khong treo
            send(ws, Map.of("type", "recognize_result", "status", "error", "reason", be.getMessage()));
            log.warn("Recognize goi backend loi: device={} - {}", deviceId, be.getMessage());
        } finally {
            activeRecognitions.decrementAndGet();
            synchronized (rt.getLock()) {                                 // Fix: luon tra ve IDLE
                if (rt.getState() == DeviceState.RECOGNIZING) rt.setState(DeviceState.IDLE);
            }
        }
    }

    // ================= Vong doi =================

    /** Fix #3: thiet bi rot WS giua chung -> huy phien enroll treo, tra state ve IDLE. */
    public void onDeviceDisconnected(String deviceId) {
        DeviceRuntime rt = registry.get(deviceId);
        if (rt == null) return;
        EnrollSession es;
        synchronized (rt.getLock()) {
            es = rt.getEnrollSession();
            rt.setEnrollSession(null);
            rt.setState(DeviceState.IDLE);
        }
        if (es != null) {
            es.getFuture().complete(Outcome.of(Status.DEVICE_DISCONNECTED));
            log.warn("Thiet bi rot WS giua enroll: device={} session={}", deviceId, es.getSessionId());
        }
    }

    // ================= noi bo =================

    private void waitForRecognitionsToDrain() {
        long deadline = System.currentTimeMillis() + priorityWait.toMillis();
        while (activeRecognitions.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void cleanupSession(DeviceRuntime rt, EnrollSession es) {
        synchronized (rt.getLock()) { cleanupSessionLocked(rt, es); }
    }

    /** Chi don neu phien hien tai dung la `es` (tranh dam len phien moi / state RECOGNIZING sau preempt). */
    private void cleanupSessionLocked(DeviceRuntime rt, EnrollSession es) {
        if (rt.getEnrollSession() == es) rt.setEnrollSession(null);
        if (rt.getState() == DeviceState.ENROLLING) rt.setState(DeviceState.IDLE);
    }

    private boolean send(WebSocketSession session, Map<String, Object> payload) {
        if (session == null || !session.isOpen()) return false;
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (session) { session.sendMessage(new TextMessage(json)); }
            return true;
        } catch (Exception e) {
            log.error("Gui WS that bai toi {}", session.getId(), e);
            return false;
        }
    }

    private static EnrollStartResult err(int http, String message) {
        return new EnrollStartResult(http, Map.of("error", message));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
