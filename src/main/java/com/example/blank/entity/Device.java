package com.example.blank.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Thiet bi ESP32 (orchestrator view-service quan ly). Khac bang gallery `identities` cua Python.
 * Trang thai phien (IDLE/RECOGNIZING/ENROLLING) giu trong bo nho, KHONG luu o day.
 */
@Getter
@Setter
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
}
