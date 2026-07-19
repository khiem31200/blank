package com.example.blank.config;

import com.example.blank.websocket.BaseWebSocketHandler;
import com.example.blank.websocket.UiNotificationHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BaseWebSocketHandler handler;      // /ws — thiết bị ESP32 (@Primary -> DeviceWebSocketHandler)
    private final UiNotificationHandler uiHandler;   // /ws/ui — dashboard trình duyệt (thông báo realtime)
    private final String[] uiAllowedOrigins;         // origin được phép cho /ws/ui (khóa cứng, không "*")

    public WebSocketConfig(BaseWebSocketHandler handler,
                           UiNotificationHandler uiHandler,
                           @Value("${ui.ws.allowed-origins}") String[] uiAllowedOrigins) {
        this.handler = handler;
        this.uiHandler = uiHandler;
        this.uiAllowedOrigins = uiAllowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Kênh để kết nối websoket với thiết bị ESP32. Cho phép tất cả origin vì ESP32 không có origin.
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
        // Kênh để kết nối websoket với ui
        registry.addHandler(uiHandler, "/ws/ui").setAllowedOrigins(uiAllowedOrigins);
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
