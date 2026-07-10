# Skill v2 — Hệ thống ESP32 Camera + Nhận diện khuôn mặt (5 luồng)

> Brief triển khai cho Claude Code. Bản này gộp và thay thế skill trước.
> Trọng tâm nằm ở **view-service (Spring Boot 4)**. Python 3ddfa GIỮ NGUYÊN, chỉ có
> **một** thay đổi duy nhất: thêm field `name` cho `/enroll` (Task P1).

---

## 0. Kiến trúc & vai trò

```
[ESP32 Cam] --wss /ws--> [Nginx 443/TLS] --> [view-service: Spring Boot 4, 8081]  = ORCHESTRATOR
                                                       |  HTTP nội bộ Docker
                                                       v
                                    [backend-service: 3ddfa (Python/FastAPI), 8080]
                                    /enroll  /recognize  /identities   + PostgreSQL(pgvector) trên RDS
```

- **view-service** giữ WebSocket, xác thực thiết bị, điều phối enroll/recognize, xoay key.
- **Python 3ddfa** chỉ làm việc AI + DB khuôn mặt (gallery `identities`, `recognition_logs`). view-service gọi lại các endpoint có sẵn của nó.
- Đã xác nhận trước đó: Spring Boot 4.1.0 / Java 21 / **Jakarta** / package gốc `com.example.blank` / có data-jpa + Postgres driver. Python chạy **1 worker uvicorn**, `/enroll` là `async def` chặn event loop.

## RÀNG BUỘC
1. KHÔNG ghi đè `nginx.conf` — chỉ **thêm** 2 location (`/ws`, `/device/register`). Giữ nguyên TLS, certbot, `client_max_body_size`, `map $http_x_api_key`.
2. KHÔNG viết lại docker-compose. Chỉ build lại image `view-app`.
3. Thay đổi Python **chỉ** ở Task P1 (thêm `name` vào enroll). Không đụng gì khác.
4. Dùng `jakarta.*`, package `com.example.blank`, không hardcode secret.
5. Mọi giao tiếp thiết bị đi trên **wss/TLS**.

---

## Bảng dữ liệu (trong DB của view-service — RDS Postgres, KHÁC bảng gallery của Python)

Bảng `devices` (dùng `ddl-auto=update` hoặc SQL tay):
```sql
CREATE TABLE IF NOT EXISTS devices (
  device_id         VARCHAR(255) PRIMARY KEY,   -- = chip id / MAC của ESP32 (cố định phần cứng)
  device_type       VARCHAR(100) DEFAULT 'ESP32_CAM',
  status            VARCHAR(50)  DEFAULT 'offline',
  current_key_hash  VARCHAR(128),
  previous_key_hash VARCHAR(128),
  pending_key_hash  VARCHAR(128),
  key_issued_at     TIMESTAMP,
  last_seen         TIMESTAMP,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```
> Trạng thái phiên (IDLE/RECOGNIZING/ENROLLING) và session WebSocket giữ **trong bộ nhớ** (ConcurrentHashMap), KHÔNG cần cột DB.

---

# LUỒNG 1 — Đăng ký thiết bị lần đầu (REST, secret chung)

Mục tiêu: thiết bị chưa có key → xin cấp key qua HTTP, rồi mới mở WS.

**Phía ESP32 (firmware):**
1. Đọc NVS: nếu **có** `apiKey` → bỏ qua, sang Luồng 2.
2. Nếu **trống**: lấy `deviceId` = **chip id / MAC từ eFuse** (cố định), gọi
   `POST https://iot-project.io.vn/device/register` body `{ "deviceId": "...", "secret": "<PROVISIONING_SECRET chung>" }`.
3. Nhận `{ "apiKey": "..." }` → **ghi vào NVS trước**, rồi sang Luồng 2.

**Phía view-service — endpoint `/device/register`:**
- Kiểm `secret` == `provisioning.secret` (đọc từ env). Sai → `401`.
- Tra `deviceId`:
  - Chưa tồn tại → tạo record, sinh key (DeviceKeyService), lưu `current_key_hash`, `key_issued_at=now`, trả key thô.
  - Đã tồn tại nhưng **chưa có** `current_key_hash` (record seed dở) → cấp key lần đầu.
  - Đã tồn tại **và đã có key** (re-register sau factory reset) → **chỉ cấp lại nếu secret hợp lệ**, coi như một lần xoay: đẩy key mới vào `pending`→ nhưng vì thiết bị mất NVS nên cấp thẳng `current` mới và dời cũ sang `previous`. Ghi log cảnh báo.
- **Idempotent theo `deviceId`**: gọi lại nhiều lần không tạo record trùng.
- Thêm **rate-limit** theo IP/deviceId (chống bot quét), vì domain public.

> Lưu ý bảo mật: secret chung chỉ chặn bot ngẫu nhiên; nếu một thiết bị bị mổ, secret lộ. Chấp nhận được cho quy mô dự án. Endpoint này PHẢI là location nginx riêng, **không** sau `map` api-key (lúc này thiết bị chưa có key).

Code (rút gọn) `.../controller/DeviceRegisterController.java`:
```java
@RestController
public class DeviceRegisterController {
    private final DeviceRegistrationService svc;
    @Value("${provisioning.secret}") String provisioningSecret;
    public DeviceRegisterController(DeviceRegistrationService svc){ this.svc=svc; }

    @PostMapping("/device/register")
    public ResponseEntity<?> register(@RequestBody RegisterReq req) {
        if (!provisioningSecret.equals(req.secret()))
            return ResponseEntity.status(401).body(Map.of("error","invalid secret"));
        String apiKey = svc.registerOrReissue(req.deviceId());   // idempotent + rotate nếu đã có
        return ResponseEntity.ok(Map.of("apiKey", apiKey));
    }
    public record RegisterReq(String deviceId, String secret) {}
}
```

---

# LUỒNG 2 — Mở kết nối WS + subscribe

1. ESP32 mở `wss://iot-project.io.vn/ws`.
2. Nginx (443) nâng cấp WebSocket → `view-service:8081` (location `/ws`, **không** api-key).
3. ESP32 gửi `{"type":"register","deviceId":"...","apiKey":"..."}`.
4. view-service: `keyService.authenticate(device, apiKey)` (chấp nhận current/previous/pending).
   - Sai → `session.close(POLICY_VIOLATION)`.
   - Đúng → lưu `deviceSessions[deviceId]=session`, đặt state `IDLE`, DB `status=online`, `last_seen=now`.
5. Gửi lại `{"type":"register_ack","status":"success","deviceId":"..."}` → thiết bị coi như đã subscribe.
6. Thiết bị gửi `heartbeat` định kỳ; rớt kết nối → `afterConnectionClosed` xóa session, DB `status=offline`.

> Spring Boot restart = mất mọi session in-memory → ESP32 phải **tự reconnect + register lại**. Firmware bắt buộc có vòng lặp reconnect.

---

# LUỒNG 3 — Đăng ký khuôn mặt (enroll, 5 ảnh, web trigger)

**Web → view-service:** `POST /api/enroll/start` body `{ "deviceId":"...", "name":"Nguyen Van A" }`.

**view-service điều phối:**
1. Kiểm state thiết bị:
   - `RECOGNIZING` → trả `409 { "error":"device busy" }` (ưu tiên recognize — quyết định C+F).
   - `ENROLLING` → `409 { "error":"already enrolling" }`.
   - Không online → `400`.
   - `IDLE` → set state `ENROLLING`, tạo `EnrollSession{ sessionId, name, images:[], expected:5, startedAt }`, khởi động **timeout** (`enroll.timeout` mặc định 30s).
2. Gửi WS lệnh xuống thiết bị: `{"type":"start_enroll","sessionId":"...","count":5}`.
3. ESP32 chụp và gửi lần lượt: `{"type":"enroll_image","sessionId":"...","deviceId":"...","image":"<base64>"}`.
4. view-service **gom ảnh** theo `sessionId`:
   - Đủ **5 ảnh** → gọi Python `POST http://backend-service:8080/enroll` body `{ "images":[...5], "name":"..." }` (Task P1 đã cho phép `name`) → nhận `{ "id", "message" }`.
   - **Timeout** trước khi đủ → hủy phiên, state `IDLE`, báo web `{"status":"timeout"}`.
   - Bị recognize chen (F) → hủy phiên, báo web `{"status":"interrupted"}`.
5. Xong → state `IDLE`, trả web `{ "status":"ok", "id":..., "name":"..." }`.

**Đồng bộ:** `/api/enroll/start` **chặn chờ** kết quả bằng `CompletableFuture` (hoàn tất khi đủ 5 ảnh xử lý xong hoặc timeout). Spring MVC là thread-per-request nên chờ được. (Muốn đổi sang poll `GET /api/enroll/status/{sessionId}` thì tách future ra, tùy chọn.)

> Nhắc: user nhập tên **cùng lúc bấm tạo user** (gửi ngay trong `/api/enroll/start`). Nếu UI muốn nhập tên **sau** khi chụp, đổi thành 2 bước: start (không tên) → nhận `id` → `POST /api/enroll/name {id,name}` (endpoint này gọi lại Python `PUT /identities/{id}`). Mặc định skill dùng cách 1 (tên đi kèm từ đầu).

---

# LUỒNG 4 — Nhận diện (recognize, ESP32 chủ động, qua WS)

**ESP32 → view-service (qua WS, tự khởi phát — D):**
`{"type":"recognize_image","deviceId":"...","image":"<base64>"}` (1 ảnh; có thể mở rộng tối đa 5).

**view-service (ưu tiên recognize — F):**
1. Xử lý ưu tiên theo state:
   - `ENROLLING` → **preempt**: hủy phiên enroll đang gom, báo web `interrupted`, chuyển `RECOGNIZING`.
   - `IDLE` → `RECOGNIZING`.
2. **Cổng ưu tiên toàn cục:** `recognize` tăng `activeRecognitions` và **không chờ** gì; trước khi enroll gọi Python `/enroll`, nó **chờ** tới khi `activeRecognitions == 0` (có cap thời gian). → recognize luôn thắng, kể cả nhiều cam chung backend.
3. Gọi Python `POST http://backend-service:8080/recognize` body `{ "images":["<base64>"] }` → `{ "identity", "confidence", "message" }`. **Python tự ghi `recognition_logs` khi khớp** (thỏa yêu cầu "cập nhật bảng log" của flow 4 — không cần view-service ghi thêm).
4. Gửi kết quả về ESP32 để **kết thúc luồng** (E):
   - Khớp: `{"type":"recognize_result","status":"ok","identity":"...","confidence":0.9x}`.
   - Dưới ngưỡng: `{"type":"recognize_result","status":"unknown","confidence":0.xx}`.
5. state `IDLE`.

> Vì đi qua WS đã xác thực từ Luồng 2, **không phát sinh bài toán api-key ở nginx** cho recognize. Đây là lý do chọn WS (thay vì REST + auth_request).

---

# State machine & loại trừ (đặt ở view-service)

Mỗi `deviceId` giữ một `DeviceRuntime{ state, enrollSession }` trong `ConcurrentHashMap`. Mọi chuyển state phải **đồng bộ theo từng thiết bị** (khóa theo deviceId) để tránh race giữa lệnh web và ảnh từ thiết bị.

| Đang | Sự kiện | Kết quả |
|---|---|---|
| IDLE | enroll start | → ENROLLING |
| IDLE | recognize_image | → RECOGNIZING |
| RECOGNIZING | enroll start | **từ chối** (device busy) — C |
| ENROLLING | recognize_image | **preempt**, hủy enroll → RECOGNIZING — F |
| ENROLLING | enroll start | từ chối (already enrolling) |
| * | xong việc | → IDLE |

Cổng ưu tiên toàn cục: `AtomicInteger activeRecognitions`. `recognize` bao quanh call Python bằng `incrementAndGet()/decrementAndGet()`, không chờ. `enroll` trước khi call Python `/enroll` chờ `activeRecognitions==0` (tối đa N giây rồi vẫn chạy để không kẹt vĩnh viễn).

---

# LUỒNG 5 — Xoay API key (design + xác thực)

Chạy hoàn toàn ở view-service + DB + WS (không đụng `map` nginx). Vì mọi giao tiếp thiết bị đã qua WS, **không cần auth_request**.

**DeviceKeyService** (đã có ở bảng `devices`): sinh key `SecureRandom` 32 byte base64url; lưu **hash SHA-256** (không lưu key thô); so sánh **constant-time** (`MessageDigest.isEqual`); 3 ô `current/previous/pending`.

```java
public boolean authenticate(DeviceInfo d, String key) {   // dùng ở Luồng 2 và re-register
    if (matches(key, d.getCurrentKeyHash()))  return true;
    if (matches(key, d.getPreviousKeyHash())) return true;
    if (matches(key, d.getPendingKeyHash())) { promote(d); return true; } // ack mất vẫn phục hồi
    return false;
}
public String startRotation(DeviceInfo d){ String k=generateKey(); d.setPendingKeyHash(hash(k)); save(d); return k; }
public void promote(DeviceInfo d){ if(d.getPendingKeyHash()==null) return;
    d.setPreviousKeyHash(d.getCurrentKeyHash()); d.setCurrentKeyHash(d.getPendingKeyHash());
    d.setPendingKeyHash(null); d.setKeyIssuedAt(Instant.now()); save(d); }
```

**Cơ chế xoay (confirm-then-promote, không brick):**
1. Scheduler (`@Scheduled`, bật `@EnableScheduling`) mỗi giờ duyệt thiết bị **online** có `key_issued_at` quá tuổi (`device.key.max-age`, vd 24h) và chưa có `pending`.
2. `startRotation` → gửi WS `{"type":"rotate_key","newKey":"...","keyId":...}` (key thô, đúng 1 lần, qua wss).
3. ESP32 **ghi NVS trước**, chuyển sang key mới, rồi gửi `{"type":"rotate_key_ack","deviceId":"...","keyId":...}`.
4. Nhận ack → `promote`. Nếu ack mất mà thiết bị đã đổi → lần reconnect `authenticate` khớp `pending` → tự promote.

**Xác thực dùng chung một chỗ:** Luồng 2 (register qua WS) và Luồng 1 (re-register) đều gọi `authenticate` / `DeviceKeyService`. Không có đường REST nào của thiết bị nên không cần validate key ở nginx.

---

# Task P1 — Thay đổi Python DUY NHẤT: thêm `name` cho `/enroll`
File `api_server.py` (chỉ sửa 2 chỗ, tận dụng hàm `update_person_name_db` đã import sẵn — KHÔNG đụng `db_utils.py`):

```python
class EnrollRequest(BaseModel):
    images: List[str] = Field(..., description="Danh sách ảnh base64.")
    name: str | None = Field(None, description="Tên người dùng (tùy chọn).")
```
Trong `enroll(...)`, ngay sau khi có `new_id`:
```python
    new_id, message = enroll_person_db(mean_emb_np, None)
    if new_id == -1:
        raise HTTPException(status_code=500, detail=message)
    if request.name:                               # <-- THÊM
        update_person_name_db(new_id, request.name)
```
> Cách này gán tên bằng hàm sẵn có, không cần sửa `db_utils.py`. Nếu tên trùng, `update_person_name_db` đã trả lỗi 409 sẵn — view-service nên bắt và báo lại web.

---

# Nginx — CHỈ THÊM (đặt trong server 443, trước `location /`)
```nginx
location /ws {
    proxy_pass http://view-service:8081;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
location /device/register {         # thiết bị chưa có key -> KHÔNG qua map api-key
    proxy_pass http://view-service:8081;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
location /api/enroll/ {             # web bấm tạo user (dashboard) — vẫn qua api-key tĩnh nếu muốn
    proxy_pass http://view-service:8081;
    proxy_read_timeout 60s;         # đủ cho enroll đồng bộ (timeout 30s + xử lý)
    proxy_set_header Host $host;
}
```
Giữ nguyên mọi thứ còn lại. `nginx -t` rồi reload.

---

# Bản tin WebSocket (tổng hợp)
```
# đăng ký / kết nối
ESP32 -> {"type":"register","deviceId","apiKey"}
srv   -> {"type":"register_ack","status":"success","deviceId"}
ESP32 -> {"type":"heartbeat","deviceId"}

# enroll (server trigger)
srv   -> {"type":"start_enroll","sessionId","count":5}
ESP32 -> {"type":"enroll_image","sessionId","deviceId","image"}   (x5)

# recognize (ESP32 chủ động)
ESP32 -> {"type":"recognize_image","deviceId","image"}
srv   -> {"type":"recognize_result","status":"ok|unknown","identity?","confidence"}

# xoay key
srv   -> {"type":"rotate_key","newKey","keyId"}
ESP32 -> {"type":"rotate_key_ack","deviceId","keyId"}

# thiết bị bận (khi bị từ chối)
srv   -> {"type":"busy","reason":"recognizing|enrolling"}
```

---

# application.properties (view-service)
```properties
recognition.base-url=http://backend-service:8080
provisioning.secret=${PROVISIONING_SECRET}          # KHÔNG hardcode
enroll.image-count=5
enroll.timeout=PT30S
device.key.max-age=PT24H
device.key.rotation-check-ms=3600000
spring.jpa.hibernate.ddl-auto=update
```

---

# Checklist nghiệm thu
- [ ] `/device/register` cấp key **idempotent** theo chip id; sai secret → 401; có rate-limit.
- [ ] ESP32 NVS trống → register REST → lưu key → mở WS → nhận `register_ack`.
- [ ] Key chỉ lưu **hash**, so sánh constant-time; không key thô trong DB/log.
- [ ] Enroll: web gửi tên + deviceId → gom đúng 5 ảnh (server đếm) → Python `/enroll` kèm name → tên vào DB.
- [ ] Enroll đang chạy mà recognize tới → enroll bị **hủy**, recognize chạy (F).
- [ ] Đang recognize mà web bấm enroll → trả **device busy** (C).
- [ ] Recognize: ESP32 tự gửi ảnh qua WS → Python `/recognize` → `recognize_result` về thiết bị → Python ghi `recognition_logs`.
- [ ] Không có đường REST nào của thiết bị cần api-key (recognize đi WS).
- [ ] Xoay key: scheduler đẩy `rotate_key`, ESP32 ghi NVS→ack→promote; ack mất vẫn phục hồi qua `pending`.
- [ ] nginx: chỉ thêm `/ws`, `/device/register`, `/api/enroll/`; TLS + `map` api-key nguyên vẹn; `nginx -t` pass.
- [ ] Python chỉ đổi đúng field `name` trong `/enroll`.

# Việc cần con người xác nhận / chuẩn bị
1. `PROVISIONING_SECRET` đặt qua env (đừng commit).
2. `deviceId` = định danh phần cứng nào (chip id vs MAC) — chốt một loại.
3. RDS Postgres cho view-service: cùng instance với Python hay tách? (bảng `devices` là của view-service).
4. Rà soát lại secret đã lộ trước đây (key EC2, mật khẩu RDS trong docker-compose Python).
