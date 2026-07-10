package com.example.blank.websocket;

import com.example.blank.BlankApplication;
import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import com.example.blank.service.DeviceKeyService;
import com.example.blank.service.KeyRotationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tu dong Luong 5 (xoay API key): rotateKey -> ack -> promote, va phuc hoi khi ack mat.
 */
@SpringBootTest(
        classes = BlankApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:rotate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "provisioning.secret=test-secret-123",
                "device.register.rate-limit.max=1000"
        })
class KeyRotationFlowTest {

    private static final String SECRET = "test-secret-123";

    @Value("${local.server.port}")
    int port;

    @Autowired DeviceRepository deviceRepository;
    @Autowired DeviceKeyService keyService;
    @Autowired KeyRotationScheduler scheduler;
    @Autowired ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---------- helpers ----------

    private String registerRest(String deviceId) throws Exception {
        String body = "{\"deviceId\":\"" + deviceId + "\",\"secret\":\"" + SECRET + "\"}";
        HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/device/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body()).path("apiKey").asText();
    }

    private WebSocketSession connect(BlockingQueue<String> inbox) throws Exception {
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) { inbox.add(m.getPayload()); }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
    }

    private WebSocketSession registerWs(String deviceId, String apiKey, BlockingQueue<String> inbox) throws Exception {
        WebSocketSession ws = connect(inbox);
        ws.sendMessage(new TextMessage(
                "{\"type\":\"register\",\"deviceId\":\"" + deviceId + "\",\"apiKey\":\"" + apiKey + "\"}"));
        String ack = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(ack, "khong nhan register_ack");
        assertEquals("success", mapper.readTree(ack).path("status").asText(), ack);
        return ws;
    }

    /** Ha key_issued_at ve qua khu de thiet bi du dieu kien xoay. */
    private void ageKey(String deviceId) {
        Device d = deviceRepository.findById(deviceId).orElseThrow();
        d.setKeyIssuedAt(Instant.now().minus(Duration.ofHours(48)));
        deviceRepository.save(d);
    }

    private void await(String msg, BooleanSupplier cond) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Timeout cho: " + msg);
    }

    // ---------- LUONG 5 ----------

    @Test
    void rotate_roi_ack_thi_promote() throws Exception {
        String id = "esp-rot-ack";
        String oldKey = registerRest(id);
        String oldHash = keyService.hash(oldKey);
        ageKey(id);

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = registerWs(id, oldKey, inbox);

        // Scheduler quet -> gui rotateKey
        scheduler.rotateStaleKeys();

        String rot = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(rot, "khong nhan rotateKey");
        JsonNode j = mapper.readTree(rot);
        assertEquals("rotateKey", j.path("type").asText(), rot);
        assertEquals(id, j.path("deviceId").asText(), rot);
        String newKey = j.path("newApiKey").asText();
        assertTrue(newKey.length() > 20, "newApiKey phai co: " + rot);
        assertTrue(j.has("timestamp"), "phai co timestamp: " + rot);

        // DB: pending da set
        assertNotNull(deviceRepository.findById(id).orElseThrow().getPendingKeyHash());

        // Thiet bi ack -> promote
        ws.sendMessage(new TextMessage(
                "{\"type\":\"ackRotateKey\",\"deviceId\":\"" + id + "\",\"status\":\"success\",\"timestamp\":\"2026-07-09T15:30:05Z\"}"));

        await("promote sau ack", () -> {
            Device d = deviceRepository.findById(id).orElseThrow();
            return d.getPendingKeyHash() == null && keyService.hash(newKey).equals(d.getCurrentKeyHash());
        });

        Device d = deviceRepository.findById(id).orElseThrow();
        assertEquals(oldHash, d.getPreviousKeyHash(), "key cu phai lui ve previous");
        ws.close();

        // Key moi xac thuc duoc
        BlockingQueue<String> inbox2 = new LinkedBlockingQueue<>();
        WebSocketSession ws2 = registerWs(id, newKey, inbox2);
        ws2.close();
    }

    @Test
    void ack_mat_van_phuc_hoi_khi_reconnect_bang_key_moi() throws Exception {
        String id = "esp-rot-lost";
        String oldKey = registerRest(id);
        ageKey(id);

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = registerWs(id, oldKey, inbox);

        scheduler.rotateStaleKeys();
        String rot = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(rot);
        String newKey = mapper.readTree(rot).path("newApiKey").asText();
        assertNotNull(deviceRepository.findById(id).orElseThrow().getPendingKeyHash());

        // Ack BI MAT: dong ket noi, khong gui ack
        ws.close();

        // Reconnect bang KEY MOI -> authenticate khop pending -> tu promote -> success
        BlockingQueue<String> inbox2 = new LinkedBlockingQueue<>();
        WebSocketSession ws2 = registerWs(id, newKey, inbox2);

        await("tu promote khi reconnect bang key moi", () -> {
            Device d = deviceRepository.findById(id).orElseThrow();
            return d.getPendingKeyHash() == null && keyService.hash(newKey).equals(d.getCurrentKeyHash());
        });
        ws2.close();
    }
}
