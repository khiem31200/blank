package com.example.blank.controller;

import com.example.blank.service.DeviceRegistrationService;
import com.example.blank.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Luong 1: thiet bi chua co key -> xin cap key qua HTTP (secret chung).
 * Endpoint nay nam o location nginx rieng, KHONG sau map api-key.
 */
@Log4j2
@RestController
public class DeviceRegisterController {

    private final DeviceRegistrationService registrationService;
    private final RateLimiter rateLimiter;

    @Value("${provisioning.secret}")
    private String provisioningSecret;

    public DeviceRegisterController(DeviceRegistrationService registrationService, RateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/device/register")
    public ResponseEntity<?> register(@RequestBody RegisterReq req, HttpServletRequest http) {
        String deviceId = req.deviceId() == null ? "" : req.deviceId().trim();

        // Rate-limit theo IP + deviceId (chong bot quet).
        String rlKey = http.getRemoteAddr() + "|" + deviceId;
        if (!rateLimiter.allow(rlKey)) {
            log.warn("Rate-limit /device/register: {}", rlKey);
            return ResponseEntity.status(429).body(Map.of("error", "too many requests"));
        }

        if (deviceId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId required"));
        }
        if (provisioningSecret == null || provisioningSecret.isEmpty()
                || !provisioningSecret.equals(req.secret())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid secret"));
        }

        String apiKey = registrationService.registerOrReissue(deviceId);
        return ResponseEntity.ok(Map.of("apiKey", apiKey, "deviceId", deviceId));
    }

    public record RegisterReq(String deviceId, String secret) {}
}
