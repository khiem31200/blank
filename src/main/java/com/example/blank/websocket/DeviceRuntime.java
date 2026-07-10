package com.example.blank.websocket;

import org.springframework.web.socket.WebSocketSession;

/**
 * Trang thai runtime cua mot thiet bi trong bo nho: session WS + state.
 * `lock` de dong bo cac chuyen state theo tung thiet bi (dung o Luong 3/4).
 */
public class DeviceRuntime {

    private final String deviceId;
    private final Object lock = new Object();
    private volatile WebSocketSession session;
    private volatile DeviceState state = DeviceState.IDLE;

    public DeviceRuntime(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceId() { return deviceId; }
    public Object getLock() { return lock; }

    public WebSocketSession getSession() { return session; }
    public void setSession(WebSocketSession session) { this.session = session; }

    public DeviceState getState() { return state; }
    public void setState(DeviceState state) { this.state = state; }
}
