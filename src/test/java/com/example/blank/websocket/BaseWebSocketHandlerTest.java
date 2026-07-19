package com.example.blank.websocket;

import com.example.blank.config.WebSocketConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test tự động cho base WebSocket: khởi động server thật trên cổng ngẫu nhiên,
 * kết nối client và kiểm chứng ping->pong, echo, và gỡ session khi đóng.
 *
 * Context tối giản: chỉ nạp WebSocketConfig + BaseWebSocketHandler, loại DataSource/JPA
 * để test chạy độc lập, không cần PostgreSQL/RDS.
 */
@SpringBootTest(
        classes = BaseWebSocketHandlerTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BaseWebSocketHandlerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class
    })
    @Import(WebSocketConfig.class) // để @EnableWebSocket được xử lý (không dùng được qua @Bean)
    static class TestApp {
        // @Primary: WebSocketConfig tiem `BaseWebSocketHandler handler` cho /ws — co 2 candidate
        // (bean nay + UiNotificationHandler la subclass) nen phai chi dinh cai nao la chinh.
        @Bean
        @Primary
        BaseWebSocketHandler baseWebSocketHandler(ObjectMapper mapper) {
            return new BaseWebSocketHandler(mapper);
        }

        // WebSocketConfig nay can UiNotificationHandler (kenh /ws/ui) -> cap trong context toi gian
        @Bean
        UiNotificationHandler uiNotificationHandler(ObjectMapper mapper) {
            return new UiNotificationHandler(mapper);
        }
    }

    @Value("${local.server.port}")
    int port;

    private WebSocketSession connect(BlockingQueue<String> inbox) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void ping_tra_ve_pong() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(inbox);

        session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));

        String resp = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp, "Không nhận được phản hồi pong");
        assertTrue(resp.contains("\"type\":\"pong\""), "Phản hồi phải có type=pong: " + resp);
        assertTrue(resp.contains("\"ts\""), "pong phải kèm timestamp: " + resp);

        session.close();
    }

    @Test
    void echo_doi_lai_payload() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(inbox);

        session.sendMessage(new TextMessage("{\"type\":\"echo\",\"hello\":\"world\"}"));

        String resp = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp, "Không nhận được phản hồi echo");
        assertTrue(resp.contains("\"type\":\"echo\""), "Phải có type=echo: " + resp);
        assertTrue(resp.contains("\"hello\":\"world\""), "Phải dội lại payload gốc: " + resp);

        session.close();
    }

    @Test
    void payload_khong_hop_le_tra_ve_error() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(inbox);

        session.sendMessage(new TextMessage("khong-phai-json"));

        String resp = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp, "Không nhận được phản hồi error");
        assertTrue(resp.contains("\"type\":\"error\""), "Phải có type=error: " + resp);
        assertTrue(resp.contains("invalid_json"), "reason phải là invalid_json: " + resp);

        session.close();
    }

    @Test
    void type_la_khong_biet_tra_ve_unknown_type() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(inbox);

        session.sendMessage(new TextMessage("{\"type\":\"khong_ton_tai\"}"));

        String resp = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp, "Không nhận được phản hồi error");
        assertTrue(resp.contains("\"type\":\"error\""), "Phải có type=error: " + resp);
        assertTrue(resp.contains("unknown_type:khong_ton_tai"), "reason phải báo unknown_type: " + resp);

        session.close();
    }
}
