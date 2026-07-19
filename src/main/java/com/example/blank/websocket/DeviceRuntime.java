package com.example.blank.websocket;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

/**
 * Trang thai runtime cua mot thiet bi trong bo nho: session WS + state.
 * `lock` de dong bo cac chuyen state theo tung thiet bi (dung o Luong 3/4).
 */
@Getter
@Setter
@RequiredArgsConstructor
public class DeviceRuntime {

    private final String deviceId;
    private final Object lock = new Object();
    private volatile WebSocketSession session;
    private volatile DeviceState state = DeviceState.IDLE;
    private volatile EnrollSession enrollSession; // phien enroll dang gom anh (Luong 3), null neu khong co
    private volatile Instant lastHeartbeatAt = Instant.now(); // -> log khoang cach toi luc rot WS khi debug mang chap chon
}
