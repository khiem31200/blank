package com.example.blank.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limiter fixed-window trong bo nho (chong bot quet /device/register vi domain public).
 * Key = "ip|deviceId". Don gian, du cho quy mo du an.
 */
@Component
public class RateLimiter {

    private final int max;
    private final long windowMs;
    // value = [windowStartMillis, count]
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${device.register.rate-limit.max:10}") int max,
                       @Value("${device.register.rate-limit.window-ms:60000}") long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    /** Tra ve true neu duoc phep, false neu vuot han muc trong cua so hien tai. */
    public synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        long[] w = hits.get(key);
        if (w == null || now - w[0] >= windowMs) {
            hits.put(key, new long[]{now, 1});
            return true;
        }
        if (w[1] < max) {
            w[1]++;
            return true;
        }
        return false;
    }
}
