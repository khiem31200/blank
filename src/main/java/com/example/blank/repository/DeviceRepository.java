package com.example.blank.repository;

import com.example.blank.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, String> {

    /** Luong 5: thiet bi online, key qua tuoi (key_issued_at < before), chua co pending. */
    List<Device> findByStatusAndPendingKeyHashIsNullAndKeyIssuedAtBefore(String status, Instant before);

    /** Danh sach thiet bi cho trang quan ly: online truoc, roi theo lan cuoi thay. */
    List<Device> findAllByOrderByStatusDescLastSeenDesc();
}
