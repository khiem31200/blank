package com.example.blank.config;

import com.example.blank.websocket.BaseWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BaseWebSocketHandler handler;

    public WebSocketConfig(BaseWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
        // ESP32 thường không gửi Origin; "*" ok cho thiết bị. Siết lại nếu có client trình duyệt.
    }

    // Nới buffer để sau này chịu được payload lớn (vd ảnh base64). Chỉnh theo nhu cầu.
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(512 * 1024);   // 512KB
        c.setMaxBinaryMessageBufferSize(512 * 1024);
        c.setMaxSessionIdleTimeout(0L);              // 0 = không tự đóng khi idle (heartbeat tự lo)
        return c;
    }
}
