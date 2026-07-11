package com.example.blank.service;

import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Luong 1: cap / cap lai API key cho thiet bi. Idempotent theo deviceId (khong tao record trung).
 */
@Log4j2
@Service
public class DeviceRegistrationService {

    private final DeviceRepository deviceRepository;
    private final DeviceKeyService keyService;

    public DeviceRegistrationService(DeviceRepository deviceRepository, DeviceKeyService keyService) {
        this.deviceRepository = deviceRepository;
        this.keyService = keyService;
    }

    /**
     * Cap key cho thiet bi, tra ve key THO (chi tra o day, DB chi luu hash).
     *  - Chua ton tai      -> tao record + cap key.
     *  - Ton tai, chua key  -> cap key lan dau.
     *  - Ton tai, da co key -> re-register (factory reset): xoay current->previous, cap current moi.
     */
    @Transactional
    public String registerOrReissue(String deviceId) {
        String rawKey = keyService.generateKey();
        String keyHash = keyService.hash(rawKey);
        Instant now = Instant.now();

        Device d = deviceRepository.findById(deviceId).orElse(null);
        if (d == null) {
            d = new Device();
            d.setDeviceId(deviceId);
            d.setStatus("offline");
            d.setCurrentKeyHash(keyHash);
            d.setKeyIssuedAt(now);
            d.setCreatedAt(now);
            deviceRepository.save(d);
            log.info("Dang ky thiet bi moi: {}", deviceId);
            return rawKey;
        }

        if (d.getCurrentKeyHash() == null) {
            d.setCurrentKeyHash(keyHash);
            d.setKeyIssuedAt(now);
            deviceRepository.save(d);
            log.info("Cap key lan dau cho record seed: {}", deviceId);
            return rawKey;
        }

        // Da co key -> re-register (thiet bi mat NVS). Coi nhu mot lan xoay: cu -> previous, moi -> current.
        log.warn("Re-register thiet bi da co key: {} (factory reset?) - cap key moi", deviceId);
        d.setPreviousKeyHash(d.getCurrentKeyHash());
        d.setCurrentKeyHash(keyHash);
        d.setPendingKeyHash(null);
        d.setKeyIssuedAt(now);
        deviceRepository.save(d);
        return rawKey;
    }
}
