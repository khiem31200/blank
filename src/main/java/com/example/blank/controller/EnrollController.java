package com.example.blank.controller;

import com.example.blank.service.FaceFlowService;
import com.example.blank.websocket.DeviceSessionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Luong 3: web/dashboard bam "tao user" -> chan cho ket qua enroll (skill v2: CompletableFuture).
 * Ten nhap CUNG LUC bam nut (di kem request ngay tu dau).
 *
 * Tra ve:
 *  400 device offline / thieu field | 409 trung ten, device busy, already enrolling
 *  200 {status: ok|timeout|interrupted|device_disconnected} | 502 loi backend
 *
 * Nginx can location /api/enroll/ voi proxy_read_timeout >= 90s (gom anh 30s + backend 20s + cho uu tien).
 */
@RestController
public class EnrollController {

    private final FaceFlowService faceFlow;
    private final DeviceSessionRegistry registry;

    public EnrollController(FaceFlowService faceFlow, DeviceSessionRegistry registry) {
        this.faceFlow = faceFlow;
        this.registry = registry;
    }

    @PostMapping("/api/enroll/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartReq req) {
        FaceFlowService.EnrollStartResult r = faceFlow.startEnroll(req.deviceId(), req.name());
        return ResponseEntity.status(r.http()).body(r.body());
    }

    /** Danh sach thiet bi online cho dropdown "chon thiet bi" (nam duoi /api/enroll/ de dung chung location nginx). */
    @GetMapping("/api/enroll/devices")
    public List<String> onlineDevices() {
        return registry.onlineDeviceIds();
    }

    public record StartReq(String deviceId, String name) {}
}
