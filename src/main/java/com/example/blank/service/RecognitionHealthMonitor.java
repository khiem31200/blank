package com.example.blank.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Theo doi suc khoe service recognize (Python) theo dinh ky (mac dinh 15s/lan).
 * Cache ket qua trong bo nho: dashboard va FaceFlowService doc gia tri cache -> KHONG chan/cho
 * moi lan load trang hay bam enroll. @EnableScheduling da bat o BlankApplication (dung chung Luong 5).
 */
@Log4j2
@Service
public class RecognitionHealthMonitor {

    private final RecognitionClient client;

    private volatile boolean healthy = false;      // mac dinh coi la loi cho toi khi ping dau tien OK
    private volatile Instant lastCheck = null;

    public RecognitionHealthMonitor(RecognitionClient client) {
        this.client = client;
    }

    public boolean isHealthy() { return healthy; }
    public Instant getLastCheck() { return lastCheck; }

    /**
     * Ping dinh ky. fixedDelay: cho lan truoc xong moi tinh 15s ke tiep (khong dồn khi backend cham).
     * initialDelay ngan de dashboard co trang thai som sau khi khoi dong.
     */
    @Scheduled(initialDelayString = "${recognition.health-check-initial-ms:2000}",
               fixedDelayString   = "${recognition.health-check-ms:15000}")
    public void check() {
        boolean now = client.isBackendHealthy();
        if (now != healthy) {
            log.info("Service recognize doi trang thai: {} -> {}", healthy ? "UP" : "DOWN", now ? "UP" : "DOWN");
        }
        healthy = now;
        lastCheck = Instant.now();
    }
}
