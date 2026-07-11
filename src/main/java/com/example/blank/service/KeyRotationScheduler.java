package com.example.blank.service;

import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import com.example.blank.websocket.DeviceSessionRegistry;
import com.example.blank.websocket.DeviceWebSocketHandler;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Luong 5: xoay API key theo co che confirm-then-promote (khong brick).
 * Dinh ky quet thiet bi ONLINE co key qua tuoi va chua co pending -> startRotation + gui rotateKey.
 * Promote xay ra khi nhan ackRotateKey (o DeviceWebSocketHandler), hoac tu phuc hoi khi reconnect
 * bang key moi (authenticate khop pending -> promote).
 */
@Log4j2
@Component
public class KeyRotationScheduler {

    private final DeviceRepository deviceRepository;
    private final DeviceKeyService keyService;
    private final DeviceWebSocketHandler handler;
    private final DeviceSessionRegistry registry;
    private final Duration maxAge;

    public KeyRotationScheduler(DeviceRepository deviceRepository,
                                DeviceKeyService keyService,
                                DeviceWebSocketHandler handler,
                                DeviceSessionRegistry registry,
                                @Value("${device.key.max-age}") Duration maxAge) {
        this.deviceRepository = deviceRepository;
        this.keyService = keyService;
        this.handler = handler;
        this.registry = registry;
        this.maxAge = maxAge;
    }

    @Scheduled(fixedDelayString = "${device.key.rotation-check-ms}",
               initialDelayString = "${device.key.rotation-check-ms}")
    public void rotateStaleKeys() {
        Instant threshold = Instant.now().minus(maxAge);
        List<Device> due = deviceRepository
                .findByStatusAndPendingKeyHashIsNullAndKeyIssuedAtBefore("online", threshold);
        if (due.isEmpty()) return;

        for (Device d : due) {
            // Chi xoay khi thuc su con session WS (tranh xoay cho thiet bi chi "online" trong DB).
            if (!registry.isOnline(d.getDeviceId())) continue;

            String newKey = keyService.startRotation(d);          // luu pending
            boolean sent = handler.sendToDevice(d.getDeviceId(),
                    Map.of("type", "rotateKey",
                            "deviceId", d.getDeviceId(),
                            "newApiKey", newKey,
                            "timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
            if (sent) {
                log.info("Da gui rotateKey cho {}", d.getDeviceId());
            } else {
                log.warn("Khong gui duoc rotateKey cho {} (session mat)", d.getDeviceId());
            }
        }
    }
}
