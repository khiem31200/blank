package com.example.blank.websocket;

import com.example.blank.BlankApplication;
import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tu dong Luong 1 (REST /device/register) + Luong 2 (WS register/heartbeat).
 * Dung full context nhung datasource H2 in-memory -> khong cham RDS that.
 */
@SpringBootTest(
        classes = BlankApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:devflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "provisioning.secret=test-secret-123",
                "device.register.rate-limit.max=1000"
        })
class DeviceFlowTest {

    private static final String SECRET = "test-secret-123";

    @Value("${local.server.port}")
    int port;

    @Autowired
    DeviceRepository deviceRepository;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> register(String deviceId, String secret) throws Exception {
        String body = "{\"deviceId\":\"" + deviceId + "\",\"secret\":\"" + secret + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/device/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private WebSocketSession connectWs(BlockingQueue<String> inbox) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }

    // ---------- LUONG 1 ----------

    @Test
    void flow1_register_cap_apiKey() throws Exception {
        HttpResponse<String> res = register("esp-flow1-ok", SECRET);
        assertEquals(200, res.statusCode(), res.body());
        JsonNode json = mapper.readTree(res.body());
        assertTrue(json.path("apiKey").asText().length() > 20, "apiKey phai duoc cap: " + res.body());
        // DB chi luu hash, khong luu key tho
        Device d = deviceRepository.findById("esp-flow1-ok").orElseThrow();
        assertNotNull(d.getCurrentKeyHash());
        assertNotEquals(json.path("apiKey").asText(), d.getCurrentKeyHash(), "DB khong duoc luu key tho");
    }

    @Test
    void flow1_sai_secret_tra_401() throws Exception {
        HttpResponse<String> res = register("esp-flow1-badsecret", "sai-secret");
        assertEquals(401, res.statusCode(), res.body());
        assertFalse(deviceRepository.existsById("esp-flow1-badsecret"), "Khong duoc tao record khi sai secret");
    }

    @Test
    void flow1_idempotent_khong_tao_record_trung() throws Exception {
        String id = "esp-flow1-idem";
        assertEquals(200, register(id, SECRET).statusCode());
        long after1 = deviceRepository.findAll().stream().filter(d -> id.equals(d.getDeviceId())).count();
        assertEquals(200, register(id, SECRET).statusCode());
        long after2 = deviceRepository.findAll().stream().filter(d -> id.equals(d.getDeviceId())).count();
        assertEquals(1, after1);
        assertEquals(1, after2, "Goi lai khong duoc tao record trung");
    }

    // ---------- LUONG 2 ----------

    @Test
    void flow2_ws_register_thanh_cong() throws Exception {
        String id = "esp-flow2-ok";
        JsonNode reg = mapper.readTree(register(id, SECRET).body());
        String apiKey = reg.path("apiKey").asText();

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = connectWs(inbox);
        ws.sendMessage(new TextMessage(
                "{\"type\":\"register\",\"deviceId\":\"" + id + "\",\"apiKey\":\"" + apiKey + "\"}"));

        String ack = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(ack, "Khong nhan duoc register_ack");
        JsonNode j = mapper.readTree(ack);
        assertEquals("register_ack", j.path("type").asText());
        assertEquals("success", j.path("status").asText(), "register phai thanh cong: " + ack);

        // DB: status online
        Device d = deviceRepository.findById(id).orElseThrow();
        assertEquals("online", d.getStatus());
        assertNotNull(d.getLastSeen());

        ws.close();
    }

    @Test
    void flow2_ws_register_sai_key_bi_tu_choi() throws Exception {
        String id = "esp-flow2-badkey";
        register(id, SECRET); // tao thiet bi that

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = connectWs(inbox);
        ws.sendMessage(new TextMessage(
                "{\"type\":\"register\",\"deviceId\":\"" + id + "\",\"apiKey\":\"KEY-SAI-HOAN-TOAN\"}"));

        String ack = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(ack, "Phai nhan register_ack fail");
        JsonNode j = mapper.readTree(ack);
        assertEquals("fail", j.path("status").asText(), "Key sai phai bi tu choi: " + ack);
    }

    @Test
    void flow2_heartbeat_truoc_register_bao_loi() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession ws = connectWs(inbox);
        ws.sendMessage(new TextMessage("{\"type\":\"heartbeat\",\"deviceId\":\"whatever\"}"));

        String resp = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp);
        JsonNode j = mapper.readTree(resp);
        assertEquals("error", j.path("type").asText());
        assertEquals("not_registered", j.path("reason").asText(), resp);

        ws.close();
    }
}
