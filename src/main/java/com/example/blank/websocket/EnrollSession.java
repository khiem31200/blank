package com.example.blank.websocket;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Phien enroll dang gom anh cua mot thiet bi (Luong 3).
 * Moi truy cap vao danh sach anh PHAI nam trong DeviceRuntime.lock cua thiet bi.
 * Ket qua cuoi cung day qua `future` de thread web (/api/enroll/start) cho dong bo.
 */
@Getter
@RequiredArgsConstructor
public class EnrollSession {

    public enum Status { OK, TIMEOUT, INTERRUPTED, DEVICE_DISCONNECTED, BACKEND_ERROR }

    /** Ket qua chot cua phien: id do Python cap (neu OK), message de bao web. */
    public record Outcome(Status status, Integer id, String message) {
        public static Outcome ok(Integer id)            { return new Outcome(Status.OK, id, null); }
        public static Outcome of(Status s)              { return new Outcome(s, null, null); }
        public static Outcome backendError(String msg)  { return new Outcome(Status.BACKEND_ERROR, null, msg); }
    }

    private final String sessionId;
    private final String deviceId;
    private final String name;
    private final int expected;
    private final Instant startedAt = Instant.now();
    private final CompletableFuture<Outcome> future = new CompletableFuture<>();

    /** Khong lo getter: anh chi duoc dung qua addImage/imageCount/snapshotImages trong lock. */
    @Getter(AccessLevel.NONE)
    private final List<String> images = new ArrayList<>();

    /** true = da gom du anh, dang goi backend -> khong duoc preempt/huy nua. */
    @Setter
    private volatile boolean processing = false;

    // ---- Thao tac anh: goi trong DeviceRuntime.lock ----
    public void addImage(String image) { images.add(image); }
    public int imageCount()            { return images.size(); }
    public List<String> snapshotImages() { return List.copyOf(images); }
}
