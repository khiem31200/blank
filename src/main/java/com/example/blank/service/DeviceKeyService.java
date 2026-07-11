package com.example.blank.service;

import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Sinh / bam / xac thuc / xoay API key cho thiet bi.
 * - Key tho: SecureRandom 32 byte, base64url khong padding.
 * - Luu HASH SHA-256 (khong bao gio luu key tho).
 * - So sanh constant-time (MessageDigest.isEqual).
 * - 3 o: current / previous / pending (confirm-then-promote, khong brick).
 */
@Log4j2
@Service
public class DeviceKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository deviceRepository;

    public DeviceKeyService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public String generateKey() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 khong kha dung", e);
        }
    }

    /** So sanh key tho voi hash da luu, constant-time. */
    private boolean matches(String key, String storedHash) {
        if (key == null || storedHash == null) return false;
        byte[] a = storedHash.getBytes(StandardCharsets.UTF_8);
        byte[] b = hash(key).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * Xac thuc key: chap nhan current / previous / pending.
     * Neu khop pending -> promote ngay (phuc hoi khi ack rotate bi mat). Dung o Luong 2 + re-register.
     */
    public boolean authenticate(Device d, String key) {
        if (matches(key, d.getCurrentKeyHash())) return true;
        if (matches(key, d.getPreviousKeyHash())) return true;
        if (matches(key, d.getPendingKeyHash())) {
            promote(d);
            return true;
        }
        return false;
    }

    /** Bat dau xoay: sinh key moi, luu vao pending, tra key tho (gui 1 lan qua wss). */
    public String startRotation(Device d) {
        String key = generateKey();
        d.setPendingKeyHash(hash(key));
        deviceRepository.save(d);
        return key;
    }

    /** Chuyen pending -> current, current -> previous. Idempotent khi khong co pending. */
    public void promote(Device d) {
        if (d.getPendingKeyHash() == null) return;
        d.setPreviousKeyHash(d.getCurrentKeyHash());
        d.setCurrentKeyHash(d.getPendingKeyHash());
        d.setPendingKeyHash(null);
        d.setKeyIssuedAt(Instant.now());
        deviceRepository.save(d);
        log.info("Promote key cho device {}", d.getDeviceId());
    }
}
