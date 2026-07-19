package com.example.blank.websocket;

import com.example.blank.BlankApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tu dong Luong 3 (enroll qua /api/enroll/start + WS) + Luong 4 (recognize qua WS).
 * Backend Python duoc GIA LAP bang com.sun.net.httpserver tren port ngau nhien
 * (fake /enroll, /recognize, /identities) -> khong can container that.
 */
@SpringBootTest(
        classes = BlankApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:faceflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "provisioning.secret=test-secret-123",
                "device.register.rate-limit.max=1000",
                "recognition.health-check-initial-ms=200",   // health UP som, tranh race voi enroll test dau tien
                "enroll.timeout=PT2S",              // timeout gom anh ngan cho test
                "enroll.priority-wait=PT1S",
                "recognition.enroll-timeout=PT5S",
                "recognition.recognize-timeout=PT5S"
        })
class EnrollRecognizeFlowTest {

    private static final String SECRET = "test-secret-123";
    private static final String EXISTING_NAME = "Ten Da Ton Tai";

    // ---- Fake backend Python (3ddfa) ----
    static HttpServer backend;
    static volatile String recognizeIdentity = "alice";   // null => khong khop ai
    static volatile String lastEnrollBody;

    @DynamicPropertySource
    static void backendProps(DynamicPropertyRegistry reg) throws IOException {
        backend = HttpServer.create(new InetSocketAddress(0), 0);
        // Health-check: RecognitionHealthMonitor ping GET "/" -> can 2xx de health=UP (neu thieu -> enroll tra 503).
        // "/" la fallback (longest-prefix), khong dam len cac context cu the ben duoi.
        backend.createContext("/", ex -> respond(ex, 200, "ok"));
        backend.createContext("/identities", ex ->
                respond(ex, 200, "[{\"id\":1,\"name\":\"" + EXISTING_NAME + "\"}]"));
        backend.createContext("/enroll", ex -> {
            lastEnrollBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, "{\"id\":42,\"message\":\"enrolled\"}");
        });
        backend.createContext("/recognize", ex -> {
            ex.getRequestBody().readAllBytes();
            String id = recognizeIdentity;
            respond(ex, 200, id == null
                    ? "{\"identity\":null,\"confidence\":0.41,\"message\":\"no match\"}"
                    : "{\"identity\":\"" + id + "\",\"confidence\":0.93,\"message\":\"ok\"}");
        });
        backend.start();
        reg.add("recognition.base-url", () -> "http://localhost:" + backend.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) backend.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---- Helper chung ----
    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Cho health-check dau tien chay xong (scheduler chay bat dong bo sau khi context len).
     * Cac test enroll can health=UP moi qua duoc chot 503 -> cho o day de bo race duoi tai full-suite.
     */
    @BeforeEach
    void waitBackendHealthy() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/enroll/health")).GET().build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            if (mapper.readTree(body).path("healthy").asBoolean()) return;
            Thread.sleep(50);
        }
    }

    record DeviceCtx(WebSocketSession ws, BlockingQueue<String> inbox) {}

    /** Luong 1 + 2: cap key REST roi register WS -> thiet bi online, san sang cho Luong 3/4. */
    private DeviceCtx onlineDevice(String deviceId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/device/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"deviceId\":\"" + deviceId + "\",\"secret\":\"" + SECRET + "\"}"))
                .build();
        String apiKey = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body())
                .path("apiKey").asText();

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);

        ws.sendMessage(new TextMessage(
                "{\"type\":\"register\",\"deviceId\":\"" + deviceId + "\",\"apiKey\":\"" + apiKey + "\"}"));
        JsonNode ack = awaitType(inbox, "register_ack", 5);
        assertEquals("success", ack.path("status").asText());
        return new DeviceCtx(ws, inbox);
    }

    /** Doi ban tin WS co type mong muon (bo qua type khac), null neu het gio. */
    private JsonNode awaitType(BlockingQueue<String> inbox, String type, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String raw = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (raw == null) return null;
            JsonNode j = mapper.readTree(raw);
            if (type.equals(j.path("type").asText())) return j;
        }
        return null;
    }

    private CompletableFuture<HttpResponse<String>> startEnrollAsync(String deviceId, String name) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/enroll/start"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"deviceId\":\"" + deviceId + "\",\"name\":\"" + name + "\"}"))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    private void sendEnrollImage(DeviceCtx c, String deviceId, String sessionId, String image) throws Exception {
        c.ws().sendMessage(new TextMessage("{\"type\":\"enroll_image\",\"sessionId\":\"" + sessionId
                + "\",\"deviceId\":\"" + deviceId + "\",\"image\":\"" + image + "\"}"));
    }

    private void sendRecognizeImage(DeviceCtx c, String deviceId) throws Exception {
        c.ws().sendMessage(new TextMessage(
                "{\"type\":\"recognize_image\",\"deviceId\":\"" + deviceId + "\",\"image\":\"anh-recog\"}"));
    }

    /** Gia lap trinh duyet dashboard: mo WS toi /ws/ui de nhan thong bao realtime (khong register). */
    private DeviceCtx openUiClient() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/ui")).get(5, TimeUnit.SECONDS);
        return new DeviceCtx(ws, inbox);
    }

    // ================= LUONG 3 =================

    @Test
    void flow3_enroll_du_5_anh_thanh_cong() throws Exception {
        String id = "esp-enroll-ok";
        DeviceCtx c = onlineDevice(id);

        var respF = startEnrollAsync(id, "Nguyen Van A");
        JsonNode cmd = awaitType(c.inbox(), "start_enroll", 5);
        assertNotNull(cmd, "Thiet bi phai nhan duoc start_enroll");
        assertEquals(5, cmd.path("count").asInt());
        String sid = cmd.path("sessionId").asText();

        for (int i = 1; i <= 5; i++) sendEnrollImage(c, id, sid, "anh-" + i);

        HttpResponse<String> resp = respF.get(15, TimeUnit.SECONDS);
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode j = mapper.readTree(resp.body());
        assertEquals("ok", j.path("status").asText(), resp.body());
        assertEquals(42, j.path("id").asInt());
        assertEquals("Nguyen Van A", j.path("name").asText());

        // Backend phai nhan du 5 anh + ten (Task P1)
        JsonNode sent = mapper.readTree(lastEnrollBody);
        assertEquals(5, sent.path("images").size());
        assertEquals("Nguyen Van A", sent.path("name").asText());

        // State phai ve IDLE: recognize ngay sau do phai chay duoc
        recognizeIdentity = "alice";
        sendRecognizeImage(c, id);
        JsonNode r = awaitType(c.inbox(), "recognize_result", 5);
        assertNotNull(r, "Sau enroll, thiet bi phai recognize duoc (state IDLE)");
        assertEquals("ok", r.path("status").asText());
        c.ws().close();
    }

    @Test
    void flow3_trung_ten_tra_409_khong_cham_thiet_bi() throws Exception {
        String id = "esp-enroll-dupname";
        DeviceCtx c = onlineDevice(id);

        HttpResponse<String> resp = startEnrollAsync(id, EXISTING_NAME).get(10, TimeUnit.SECONDS);
        assertEquals(409, resp.statusCode(), resp.body());

        // Fix #1: khong duoc gui start_enroll khi ten trung
        assertNull(awaitType(c.inbox(), "start_enroll", 1), "Ten trung thi khong duoc bao thiet bi chup");
        c.ws().close();
    }

    @Test
    void flow3_thieu_anh_timeout_va_bo_qua_anh_lac_phien() throws Exception {
        String id = "esp-enroll-timeout";
        DeviceCtx c = onlineDevice(id);

        var respF = startEnrollAsync(id, "Nguoi Timeout");
        JsonNode cmd = awaitType(c.inbox(), "start_enroll", 5);
        assertNotNull(cmd);

        // Fix #2a: anh cua phien khac phai bi bo qua im lang (khong error, khong tinh vao dem)
        for (int i = 1; i <= 5; i++) sendEnrollImage(c, id, "session-lac", "anh-" + i);

        HttpResponse<String> resp = respF.get(15, TimeUnit.SECONDS);
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("timeout", mapper.readTree(resp.body()).path("status").asText(), resp.body());

        // Fix #2b: thiet bi phai nhan stop_enroll de dung chup
        assertNotNull(awaitType(c.inbox(), "stop_enroll", 5), "Timeout phai gui stop_enroll");
        c.ws().close();
    }

    @Test
    void flow3_dang_enroll_bam_enroll_tiep_tra_409() throws Exception {
        String id = "esp-enroll-busy";
        DeviceCtx c = onlineDevice(id);

        var first = startEnrollAsync(id, "Nguoi Thu Nhat");
        JsonNode cmd = awaitType(c.inbox(), "start_enroll", 5);
        assertNotNull(cmd);

        HttpResponse<String> second = startEnrollAsync(id, "Nguoi Thu Hai").get(10, TimeUnit.SECONDS);
        assertEquals(409, second.statusCode(), second.body());
        assertTrue(second.body().contains("already enrolling"), second.body());

        // Ket thuc phien dau sach se
        String sid = cmd.path("sessionId").asText();
        for (int i = 1; i <= 5; i++) sendEnrollImage(c, id, sid, "anh-" + i);
        assertEquals(200, first.get(15, TimeUnit.SECONDS).statusCode());
        c.ws().close();
    }

    @Test
    void enroll_devices_liet_ke_thiet_bi_online() throws Exception {
        String id = "esp-devices-list";
        DeviceCtx c = onlineDevice(id);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/enroll/devices")).GET().build();
        String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(body.contains(id), "Thiet bi online phai co trong danh sach: " + body);

        c.ws().close();
        // Sau khi dong WS -> khong con trong danh sach
        Thread.sleep(300);
        String after = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        assertFalse(after.contains(id), "Thiet bi offline phai bien khoi danh sach: " + after);
    }

    @Test
    void flow3_thiet_bi_offline_tra_400() throws Exception {
        HttpResponse<String> resp = startEnrollAsync("esp-khong-ton-tai", "Ai Do").get(10, TimeUnit.SECONDS);
        assertEquals(400, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("device offline"), resp.body());
    }

    // ================= LUONG 4 =================

    @Test
    void flow4_recognize_khop_tra_ok() throws Exception {
        String id = "esp-recog-ok";
        DeviceCtx c = onlineDevice(id);
        recognizeIdentity = "alice";

        sendRecognizeImage(c, id);
        JsonNode r = awaitType(c.inbox(), "recognize_result", 5);
        assertNotNull(r, "Phai nhan recognize_result");
        assertEquals("ok", r.path("status").asText(), r.toString());
        assertEquals("alice", r.path("identity").asText());
        assertTrue(r.path("confidence").asDouble() > 0.9);
        c.ws().close();
    }

    @Test
    void flow4_recognize_khong_khop_tra_unknown() throws Exception {
        String id = "esp-recog-unknown";
        DeviceCtx c = onlineDevice(id);
        recognizeIdentity = null; // backend khong khop ai

        try {
            sendRecognizeImage(c, id);
            JsonNode r = awaitType(c.inbox(), "recognize_result", 5);
            assertNotNull(r);
            assertEquals("unknown", r.path("status").asText(), r.toString());
            assertFalse(r.has("identity"), "unknown khong duoc kem identity");
        } finally {
            recognizeIdentity = "alice";
        }
        c.ws().close();
    }

    @Test
    void flow4_recognize_khop_ban_thong_bao_len_dashboard() throws Exception {
        DeviceCtx ui = openUiClient();
        String id = "esp-recog-ui";
        DeviceCtx c = onlineDevice(id);
        recognizeIdentity = "alice";

        sendRecognizeImage(c, id);

        // Thiet bi van nhan recognize_result ok nhu cu
        JsonNode r = awaitType(c.inbox(), "recognize_result", 5);
        assertNotNull(r);
        assertEquals("ok", r.path("status").asText());

        // Dashboard (/ws/ui) phai nhan thong bao "recognized" de bat toast
        JsonNode n = awaitType(ui.inbox(), "recognized", 5);
        assertNotNull(n, "Dashboard phai nhan thong bao recognized khi match");
        assertEquals("alice", n.path("identity").asText());
        assertEquals(id, n.path("deviceId").asText());
        assertTrue(n.path("confidence").asDouble() > 0.9, n.toString());
        assertTrue(n.path("ts").asLong() > 0, "phai co timestamp");

        ui.ws().close();
        c.ws().close();
    }

    @Test
    void flow4_recognize_khong_khop_khong_ban_len_dashboard() throws Exception {
        DeviceCtx ui = openUiClient();
        String id = "esp-recog-ui-unknown";
        DeviceCtx c = onlineDevice(id);
        recognizeIdentity = null; // backend khong khop ai

        try {
            sendRecognizeImage(c, id);
            JsonNode r = awaitType(c.inbox(), "recognize_result", 5);
            assertNotNull(r);
            assertEquals("unknown", r.path("status").asText());

            // Khong khop -> KHONG duoc ban "recognized" len dashboard
            assertNull(awaitType(ui.inbox(), "recognized", 2),
                    "Unknown khong duoc ban recognized len dashboard");
        } finally {
            recognizeIdentity = "alice";
        }
        ui.ws().close();
        c.ws().close();
    }

    @Test
    void flow4_recognize_preempt_enroll_dang_gom() throws Exception {
        String id = "esp-recog-preempt";
        DeviceCtx c = onlineDevice(id);
        recognizeIdentity = "alice";

        var respF = startEnrollAsync(id, "Nguoi Bi Chen");
        JsonNode cmd = awaitType(c.inbox(), "start_enroll", 5);
        assertNotNull(cmd);

        // Recognize chen ngang khi enroll moi gom 0 anh (F: recognize uu tien)
        sendRecognizeImage(c, id);

        // Web phai nhan interrupted
        HttpResponse<String> resp = respF.get(15, TimeUnit.SECONDS);
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("interrupted", mapper.readTree(resp.body()).path("status").asText(), resp.body());

        // Thiet bi phai nhan stop_enroll (Fix #2b) roi recognize_result
        assertNotNull(awaitType(c.inbox(), "stop_enroll", 5), "Preempt phai gui stop_enroll");
        JsonNode r = awaitType(c.inbox(), "recognize_result", 5);
        assertNotNull(r);
        assertEquals("ok", r.path("status").asText());
        c.ws().close();
    }

    @Test
    void flow4_recognize_truoc_khi_register_bao_loi() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);

        ws.sendMessage(new TextMessage("{\"type\":\"recognize_image\",\"deviceId\":\"x\",\"image\":\"anh\"}"));
        JsonNode j = awaitType(inbox, "error", 5);
        assertNotNull(j);
        assertEquals("not_registered", j.path("reason").asText());
        ws.close();
    }
}
