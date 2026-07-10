# HANDOVER — view-service (ESP32 orchestrator)

> Ghi lại toàn bộ việc đã làm để session/người khác tiếp tục. Cập nhật: 2026-07-10.
> Repo: `view-service` (Spring Boot 4.1, Java 21, package `com.example.blank`). Branch: `feature/web-socket`.

---

## 1. Bối cảnh & kiến trúc

```
[ESP32-CAM] --wss /ws--> [Nginx 443/TLS] --> [view-service : Spring Boot 4, 8081]  = ORCHESTRATOR
                          (iot-project.io.vn)          |  HTTP noi bo Docker
                                                       v
                                    [backend-service : 3ddfa Python/FastAPI, 8080] + PostgreSQL(RDS)
```

- **view-service** (repo này): giữ WebSocket, xác thực thiết bị, xoay key; sau này điều phối enroll/recognize.
- **backend-service** (repo Python `3ddfa`, KHÁC repo): AI khuôn mặt + gallery `identities`/`recognition_logs`.
- Toàn bộ dựa trên **skill v2** (5 luồng). Bản gốc skill do người dùng cung cấp.

---

## 2. Trạng thái: ĐÃ LÀM vs CHƯA LÀM

### ✅ Đã code + test (local 13/13 + đã verify trên cloud)
| Luồng | Nội dung |
|-------|----------|
| **Base WS** | Đã **commit** `fa2ed91`: `WebSocketConfig`, `BaseWebSocketHandler` (ping/echo, registry theo sessionId, điểm mở rộng `handleBusinessMessage`), test. |
| **Luồng 1** | REST `POST /device/register` — secret chung → cấp apiKey, idempotent, rate-limit. |
| **Luồng 2** | WS `register` (xác thực key) → `register_ack`, `heartbeat`, đóng→offline. |
| **Luồng 5** | Xoay key: scheduler đẩy `rotateKey` → `ackRotateKey` → promote; phục hồi qua `pending` khi ack mất. |

### ❌ CHƯA làm (đừng giả định đã có)
- **Luồng 3 (enroll)** và **Luồng 4 (recognize)** — chưa code. Message `start_enroll`/`enroll_image`/`recognize_image`/`recognize_result` **server chưa xử lý** (rơi vào `unknown_type`).
- **Task P1 (Python)**: thêm field `name` cho `/enroll` ở repo `3ddfa` — chưa làm (chỉ cần khi làm Luồng 3).
- `RecognitionClient` gọi `backend-service:8080` — chưa có.
- Scheduler xoay key chỉ chạy theo lịch (mỗi 1h, key >24h); **không có endpoint trigger tay**.

---

## 3. Bản đồ file (uncommitted trên `feature/web-socket`)

```
src/main/java/com/example/blank/
  entity/Device.java                     # bang `devices` (current/previous/pending key hash)
  repository/DeviceRepository.java        # + finder cho scheduler
  service/DeviceKeyService.java           # gen/hash(SHA-256 hex)/authenticate/startRotation/promote
  service/DeviceRegistrationService.java  # Luong 1: registerOrReissue (idempotent + rotate)
  service/RateLimiter.java                # fixed-window in-memory theo ip|deviceId
  service/KeyRotationScheduler.java       # Luong 5: @Scheduled quet + gui rotateKey
  controller/DeviceRegisterController.java# Luong 1: POST /device/register
  websocket/DeviceWebSocketHandler.java   # @Primary, extends Base; register/heartbeat/ackRotateKey
  websocket/DeviceSessionRegistry.java    # ConcurrentHashMap<deviceId, DeviceRuntime>
  websocket/DeviceRuntime.java            # session + DeviceState + lock
  websocket/DeviceState.java              # IDLE/RECOGNIZING/ENROLLING (moi IDLE duoc dung)
  BlankApplication.java                   # + @EnableScheduling
src/main/resources/application.properties # + provisioning.secret, device.key.*, ddl-auto=update
src/test/java/com/example/blank/websocket/
  DeviceFlowTest.java                     # Luong 1+2 (H2)
  KeyRotationFlowTest.java                # Luong 5 (H2)
docs/esp32-integration.md                 # hand-down cho firmware ESP32 (chi chuc nang da co)
docs/HANDOVER.md                          # file nay
```
> `pom.xml`: thêm `spring-boot-starter-websocket`, `spring-boot-starter-json`, `h2` (test).

---

## 4. Protocol WebSocket (phần đã có)

Xem chi tiết ở `docs/esp32-integration.md`. Tóm tắt:
```
Device -> {"type":"register","deviceId","apiKey"}         Server -> {"type":"register_ack","status":"success|fail","deviceId"}
Device -> {"type":"heartbeat","deviceId"}                 (khong reply; last_seen cap nhat)
Server -> {"type":"rotateKey","deviceId","newApiKey","timestamp"}   # scheduler day
Device -> {"type":"ackRotateKey","deviceId","status":"success","timestamp"}  # -> promote neu success
debug:  {"type":"ping"} -> {"type":"pong","ts"} ; {"type":"echo",...}
loi:    {"type":"error","reason":"not_registered|invalid_json|unknown_type:<t>"}
```
> ⚠️ Schema rotate dùng **camelCase** (`rotateKey`/`ackRotateKey`/`newApiKey`) — đã đổi theo yêu cầu người dùng (trước đó là `rotate_key`/`newKey`/`keyId`). Cơ chế promote/pending KHÔNG đổi.

---

## 5. Build & test LOCAL

```bash
./mvnw.cmd test           # full suite: 13 test (BlankApplicationTests, BaseWebSocketHandlerTest, DeviceFlowTest, KeyRotationFlowTest)
./mvnw.cmd -Dtest=DeviceFlowTest test
```
- Test dùng **H2 in-memory** (`@SpringBootTest(properties=...)` override datasource) → **không cần RDS**.
- ⚠️ Bẫy đã gặp: `DeviceFlowTest`/`KeyRotationFlowTest` phải khai báo `classes = BlankApplication.class`. Vì cùng package `...websocket` với `BaseWebSocketHandlerTest$TestApp` (một `@SpringBootConfiguration` tối giản) → Spring Boot nhặt nhầm config đó → thiếu JPA beans.
- ⚠️ `BlankApplicationTests` phải `webEnvironment=RANDOM_PORT` (bean `ServletServerContainerFactoryBean` cần servlet container thật).

---

## 6. Hạ tầng & Deploy (EC2)

- EC2: `ec2-user@13.213.16.139`, SSH key `iot-ec2-key.pem` (ở gốc repo, KHÔNG commit).
- Container: `ec2-user-view-service-1` (8081), `ec2-user-nginx-1`, `ec2-user-backend-service-1`. Compose: `/home/ec2-user/docker-compose.yaml`. Nginx conf host: `/home/ec2-user/nginx.conf`.
- Deploy: `.\implement.ps1 -BuildArgs @{ APP_ENV="prod"; PORT="8081" }` (build→ECR→SSH `deploy_ec2.ps1`→`up -d --no-deps view-service`).

### Bẫy deploy (ĐÃ gặp — nhớ để khỏi mất thời gian)
1. **ECR login 400** nếu chạy `implement.ps1` bằng **Windows PowerShell 5.1** (pipe password bị BOM/CRLF). ⇒ chạy bằng **pwsh 7**.
2. **nginx sửa file KHÔNG tự áp dụng** — phải `sudo docker exec ec2-user-nginx-1 nginx -t && nginx -s reload`. (`nginx -T` đọc file đĩa nên gây nhầm là đã áp dụng.)
3. **`PROVISIONING_SECRET` bắt buộc** — `application.properties` có `provisioning.secret=${PROVISIONING_SECRET}` (không default). Thiếu env → app crash-loop placeholder → nginx 502. Env đặt trong block `view-service` của compose. Lấy giá trị: `ssh ... "grep PROVISIONING /home/ec2-user/docker-compose.yaml"` (KHÔNG ghi giá trị vào repo).
4. nginx đã có sẵn location `/ws` và `/device/register` (proxy `view-service:8081`); api-key gate ở `location /` đã được comment.

### Kiểm tra / thao tác DB trên RDS (từ EC2)
RDS Postgres dùng chung với Python (`face_recog_db`, user `postgres`). Chạy psql qua container trên EC2:
```bash
ssh -i iot-ec2-key.pem ec2-user@13.213.16.139 \
 "sudo docker run --rm -e PGPASSWORD=postgres postgres:16 psql \
  -h database-iot.c7oewgusah9l.ap-southeast-1.rds.amazonaws.com -U postgres -d face_recog_db \
  -c 'SELECT * FROM devices;'"
```
view-service tự tạo bảng `devices` bằng `ddl-auto=update`.

---

## 7. Cách TEST CLOUD (đã dùng, tái sử dụng được)

- **Luồng 1**: `curl -X POST https://iot-project.io.vn/device/register -d '{"deviceId":"x","secret":"<SECRET>"}'`.
- **Luồng 2**: WS client (script PowerShell `System.Net.WebSockets.ClientWebSocket`, nhớ bật TLS 1.2). Gửi `register` → chờ `register_ack`.
- **Luồng 5** (scheduler không trigger tay được): **mô phỏng** trạng thái `pending` mà scheduler tạo ra, bằng cách set thẳng vào RDS:
  1. Register lấy `oldKey`. Chọn `newKey` bất kỳ, tính `SHA256_hex(newKey)`.
  2. `UPDATE devices SET pending_key_hash='<sha256hex>' WHERE device_id='...';`
  3. **Recovery path**: WS `register` bằng `newKey` → `authenticate` khớp pending → auto-promote.
  4. **Ack path**: WS `register` bằng `oldKey` → gửi `ackRotateKey`(status:success) → promote.
  5. Verify `devices`: `current_key_hash == SHA256(newKey)`, `pending_key_hash IS NULL`, `previous == SHA256(oldKey)`.
- Nhớ **DELETE** device test khỏi RDS sau khi xong.
- Script tạm đã dùng nằm ở scratchpad (session cũ) — không commit; viết lại theo hướng dẫn trên nếu cần.

---

## 8. Quyết định thiết kế & bẫy kỹ thuật

- **Jackson 3**: Spring Boot 4 dùng Jackson 3 → import `tools.jackson.databind.*` (KHÔNG phải `com.fasterxml.jackson.*`). Cần thêm `spring-boot-starter-json` vì project chỉ có `webmvc`+thymeleaf.
- **Handler injection**: `DeviceWebSocketHandler` `@Primary` extends `BaseWebSocketHandler` → `WebSocketConfig` tự chọn nó; **base không sửa**.
- **Key an toàn**: `DeviceKeyService` lưu **SHA-256 hex**, so sánh **constant-time** (`MessageDigest.isEqual`); 3 ô current/previous/pending → xoay không brick.
- **State machine**: `DeviceState` + `DeviceRuntime.lock` đã có sẵn khung cho Luồng 3/4 (mới dùng IDLE).
- **ddl-auto=update** trên RDS dùng chung — chỉ thêm bảng `devices`, không xoá. Cân nhắc nếu tách DB (skill mục cần-xác-nhận #3).

---

## 9. Việc tiếp theo gợi ý (theo skill v2)

1. **Luồng 3 (enroll)**: `POST /api/enroll/start {deviceId,name}` → set state ENROLLING, gửi WS `start_enroll`, gom 5 ảnh (`enroll_image`) → gọi Python `/enroll` (kèm `name`) → trả web bằng `CompletableFuture`. Cần nginx thêm `/api/enroll/`. Cần Task P1 (Python).
2. **Luồng 4 (recognize)**: WS `recognize_image` → gọi Python `/recognize` → `recognize_result`. Ưu tiên recognize (preempt enroll), cổng `AtomicInteger activeRecognitions`.
3. State machine loại trừ (IDLE/RECOGNIZING/ENROLLING) khoá theo deviceId.
4. `RecognitionClient` gọi `backend-service:8080` (`recognition.base-url` đã có trong properties).

---

## 10. Lưu ý bàn giao
- Toàn bộ Luồng 1/2/5 + docs **CHƯA commit** (working tree `feature/web-socket`). Base WS đã commit `fa2ed91`.
- Không commit `iot-ec2-key.pem` và giá trị `PROVISIONING_SECRET`.
- Người dùng giao tiếp bằng tiếng Việt; comment code cũng tiếng Việt (không dấu ở file .java để tránh lỗi encoding).
