package com.example.blank.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry phien theo deviceId (tang nghiep vu, tren base). Giu DeviceRuntime cho moi thiet bi.
 * Spring Boot restart -> mat het -> thiet bi phai tu reconnect + register lai (Luong 2).
 */
@Component
public class DeviceSessionRegistry {

    private final Map<String, DeviceRuntime> runtimes = new ConcurrentHashMap<>();

    /** Gan session cho thiet bi, dat state IDLE. Tra ve runtime tuong ung. */
    public DeviceRuntime bind(String deviceId, WebSocketSession session) {
        DeviceRuntime rt = runtimes.computeIfAbsent(deviceId, DeviceRuntime::new);
        rt.setSession(session);
        rt.setState(DeviceState.IDLE);
        return rt;
    }

    public DeviceRuntime get(String deviceId) {
        return runtimes.get(deviceId);
    }

    public WebSocketSession session(String deviceId) {
        DeviceRuntime rt = runtimes.get(deviceId);
        return rt == null ? null : rt.getSession();
    }

    public void remove(String deviceId) {
        runtimes.remove(deviceId);
    }

    public boolean isOnline(String deviceId) {
        DeviceRuntime rt = runtimes.get(deviceId);
        return rt != null && rt.getSession() != null && rt.getSession().isOpen();
    }

    public int online() {
        return runtimes.size();
    }
}
