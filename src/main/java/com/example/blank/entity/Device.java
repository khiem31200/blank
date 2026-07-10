package com.example.blank.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Thiet bi ESP32 (orchestrator view-service quan ly). Khac bang gallery `identities` cua Python.
 * Trang thai phien (IDLE/RECOGNIZING/ENROLLING) giu trong bo nho, KHONG luu o day.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @Column(name = "device_id")
    private String deviceId;                 // = chip id / MAC cua ESP32 (co dinh phan cung)

    @Column(name = "device_type", length = 100)
    private String deviceType = "ESP32_CAM";

    @Column(length = 50)
    private String status = "offline";

    @Column(name = "current_key_hash", length = 128)
    private String currentKeyHash;

    @Column(name = "previous_key_hash", length = 128)
    private String previousKeyHash;

    @Column(name = "pending_key_hash", length = 128)
    private String pendingKeyHash;

    @Column(name = "key_issued_at")
    private Instant keyIssuedAt;

    @Column(name = "last_seen")
    private Instant lastSeen;

    @Column(name = "created_at")
    private Instant createdAt;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrentKeyHash() { return currentKeyHash; }
    public void setCurrentKeyHash(String currentKeyHash) { this.currentKeyHash = currentKeyHash; }

    public String getPreviousKeyHash() { return previousKeyHash; }
    public void setPreviousKeyHash(String previousKeyHash) { this.previousKeyHash = previousKeyHash; }

    public String getPendingKeyHash() { return pendingKeyHash; }
    public void setPendingKeyHash(String pendingKeyHash) { this.pendingKeyHash = pendingKeyHash; }

    public Instant getKeyIssuedAt() { return keyIssuedAt; }
    public void setKeyIssuedAt(Instant keyIssuedAt) { this.keyIssuedAt = keyIssuedAt; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
