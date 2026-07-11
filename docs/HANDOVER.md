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

### ✅ Đã code + test (local 22/22; Luồng 1/2/5 đã verify trên cloud, Luồng 3/4 CHƯA verify cloud)
| Luồng | Nội dung |
|-------|----------|
| **Base WS** | Đã **commit** `fa2ed91`: `WebSocketConfig`, `BaseWebSocketHandler` (ping/echo, registry theo sessionId, điểm mở rộng `handleBusinessMessage`), test. |
| **Luồng 1** | REST `POST /device/register` — secret chung → cấp apiKey, idempotent, rate-limit. |
| **Luồng 2** | WS `register` (xác thực key) → `register_ack`, `heartbeat`, đóng→offline. |
| **Luồng 3** | `POST /api/enroll/start {deviceId,name}` (blocking) → check trùng tên qua `GET /identities` TRƯỚC khi chụp → WS `start_enroll` → gom 5 `enroll_image` → Python `/enroll` kèm `name` → trả web `ok/timeout/interrupted/device_disconnected/error`. Timeout gom ảnh 30s, gửi `stop_enroll` khi hủy. |
| **Luồng 4** | WS `recognize_image` (nút bấm vật lý trên ESP32) → Python `/recognize` → `recognize_result` (`ok/unknown/error`). Preempt enroll đang gom (F), `busy` khi spam nút hoặc đang recognize (C), cổng `activeRecognitions` (enroll chờ recognize xong, cap `enroll.priority-wait`). |
| **Luồng 5** | Xoay key: scheduler đẩy `rotateKey` → `ackRotateKey` → promote; phục hồi qua `pending` khi ack mất. |

### ✅ E2E LOCAL đã verify (2026-07-11): CẢ 5 LUỒNG THÔNG 13/13
Script giả lập ESP32 (PowerShell WS client) chạy đủ 5 luồng với **Python 3ddfa thật chạy local :8080** + RDS thật:
register 401/200 → WS register fail/success/online → enroll 5 ảnh (ảnh emma.jpg resize 480px) → `{"id":4,"status":"ok","name":...}` → tên vào `/identities` (S3 image_url hoạt động) → trùng tên 409 → recognize `ok` confidence 0.99 → `recognition_logs` +1 → xoay key recovery qua pending. Dọn sạch data test sau khi chạy.
- ⚠️ **Bẫy tìm ra khi e2e**: JDK HttpClient mặc định thử HTTP/2 (`Upgrade: h2c` với `http://`) → uvicorn bỏ qua body → FastAPI 422 "body missing". Đã fix: `RecognitionClient` ép `HTTP_1_1`.
- **Task P1 đã có trên bản Python local của Khảm** (`EnrollRequest` có `name`, xác nhận qua openapi.json). Cần xác nhận bản **trên EC2** đã deploy chưa.

### ☁️ CLOUD đã verify (2026-07-11): CẢ 5 LUỒNG THÔNG 13/13 trên https://iot-project.io.vn
E2E cloud (script như mục local, đổi base wss/https + secret prod): register 401/200 → WSS register → enroll 5 ảnh qua nginx `/api/enroll/` → Python cloud (Task P1 ĐÃ deploy) → trùng tên 409 → recognize ok 0.99 + `recognition_logs` +1 (default `now()` hotfix hoạt động) → xoay key recovery. Lưu ý nhỏ: identity enroll qua luồng này có `image_url: null` (S3 upload phía backend không chạy với ảnh test? — theo dõi thêm, việc của backend).

### Sự cố deploy đã xử lý (2026-07-11) — ĐỌC KỸ TRƯỚC LẦN DEPLOY SAU
1. **Compose trên EC2 bị ghi đè image**: `view-service` bị trỏ sang `3ddfa-v2-app:latest` (image Python!) → mọi route view-service 502. Nghi phạm: `deploy_ec2.ps1` chạy **không có `-ServiceName`** → sed global đè image mọi service. Đã sửa lại `view-app:latest` + pull + up. ⇒ **Luôn truyền `-ServiceName` khi deploy**; dặn cả Khảm.
2. **Bẫy bind-mount file đơn**: `sed -i` vào `/home/ec2-user/nginx.conf` tạo **inode mới** → container nginx (bind-mount đúng file đó) vẫn đọc inode cũ; `nginx -t`/`reload` pass nhưng là **conf cũ**. Fix: `docker restart ec2-user-nginx-1` để mount lại. (Trap này ĐÈ LÊN trap "nginx reload" cũ — reload thôi chưa đủ nếu sửa file bằng sed -i/editor tạo file mới.)
3. Nginx đã CÓ location `/api/enroll/` → view-service:8081, `proxy_read_timeout 90s` (thêm 2026-07-11, trước `location /view`).

### ❌ CHƯA làm (đừng giả định đã có)
- `/api/enroll/start` **chưa có auth** (api-key gate ở nginx `location /` đang bị comment) — chấp nhận cho demo, cần chốt trước khi public.
- Scheduler xoay key chỉ chạy theo lịch (mỗi 1h, key >24h); **không có endpoint trigger tay**.
- `iot-ec2-key.pem`/`.ppk` **đang bị track trên git + đã lên GitHub từ Initial commit** — cần gỡ tracking + đổi key pair EC2.

### Quyết định đã chốt với người dùng (2026-07-10) cho Luồng 3/4
- Tên nhập **cùng lúc bấm tạo user** (đi trong `/api/enroll/start`), không phải sau khi chụp.
- Check trùng tên TRƯỚC khi bảo thiết bị chụp (chặn identity mồ côi: Python tạo record trước, gán tên sau).
- Ảnh phiên chết (sessionId lạ / đã hủy) → bỏ qua im lặng; gửi `stop_enroll` khi timeout/preempt.
- Rớt WS giữa enroll → complete future `device_disconnected`, state về IDLE.
- Đang RECOGNIZING mà ảnh recognize mới tới → `busy` (không xếp hàng).
- Enroll đã đủ ảnh & đang gọi Python (`processing=true`) → KHÔNG preempt nữa, recognize nhận `busy`.
- Ảnh base64 > 400.000 ký tự bị từ chối (`device.image.max-base64-length`, WS buffer 512KB).

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
  service/RecognitionClient.java          # goi Python /enroll /recognize /identities (JDK HttpClient + timeout)
  service/FaceFlowService.java            # Luong 3+4: state machine, preempt, cong activeRecognitions
  controller/DeviceRegisterController.java# Luong 1: POST /device/register
  controller/EnrollController.java        # Luong 3: POST /api/enroll/start (blocking)
  websocket/DeviceWebSocketHandler.java   # @Primary, extends Base; register/heartbeat/ackRotateKey/enroll_image/recognize_image
  websocket/DeviceSessionRegistry.java    # ConcurrentHashMap<deviceId, DeviceRuntime>
  websocket/DeviceRuntime.java            # session + DeviceState + lock + enrollSession
  websocket/DeviceState.java              # IDLE/RECOGNIZING/ENROLLING (da dung du 3 state)
  websocket/EnrollSession.java            # phien gom anh + CompletableFuture<Outcome> + flag processing
  BlankApplication.java                   # + @EnableScheduling
src/main/resources/application.properties # + provisioning.secret, device.key.*, recognition.*-timeout,
                                          #   enroll.priority-wait, device.image.max-base64-length
src/test/java/com/example/blank/websocket/
  DeviceFlowTest.java                     # Luong 1+2 (H2)
  KeyRotationFlowTest.java                # Luong 5 (H2)
  EnrollRecognizeFlowTest.java            # Luong 3+4 (H2 + fake Python bang com.sun.net.httpserver)
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
# Luong 3 (web bam POST /api/enroll/start {deviceId,name} -> blocking)
Server -> {"type":"start_enroll","sessionId","count":5}
Device -> {"type":"enroll_image","sessionId","deviceId","image"}    # x5, base64 < 400k chars
Server -> {"type":"stop_enroll","sessionId"}                        # timeout / bi preempt -> dung chup
# Luong 4 (nut bam vat ly)
Device -> {"type":"recognize_image","deviceId","image"}
Server -> {"type":"recognize_result","status":"ok|unknown|error","identity?","confidence?","reason?"}
Server -> {"type":"busy","reason":"recognizing|enrolling"}
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
- **Chạy server local**: cần env `PROVISIONING_SECRET` (thiếu → crash placeholder ngay khi start) và port 8081 trên máy dev đang bị container `docker-mongo-express-1` (project khác) chiếm → dùng `SERVER_PORT=8082`. Đã có sẵn run config IntelliJ `.run/BlankApplication.run.xml` (nút Run "BlankApplication") set đủ 2 env này. Chạy CLI: `$env:PROVISIONING_SECRET='dev-secret-local'; $env:SERVER_PORT='8082'; .\mvnw.cmd spring-boot:run`.
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
- **Simulator ESP32** (`scripts/esp32-sim.ps1`, đã verify 2026-07-11): giả lập thiết bị thật để test UI/luồng local — tự register (Luồng 1+2), tự trả 5 ảnh khi nhận `start_enroll` (Luồng 3), phím `R` = bấm nút nhận diện (Luồng 4), tự ack `rotateKey` (Luồng 5), tự reconnect. Tham số: `-EnrollImages 2` để demo timeout, `-BaseUrl`, `-DeviceId`. Chạy: `powershell -File scripts\esp32-sim.ps1` (cần server local + Python :8080).
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
- **Lombok** (refactor 2026-07-11): entity (`Device`/`Identity`/`RecognitionLog`) + `DeviceRuntime`/`EnrollSession` dùng `@Getter @Setter @RequiredArgsConstructor`; logger dùng `@Log4j2`. `EnrollSession.images` để `@Getter(AccessLevel.NONE)` — chỉ truy cập qua addImage/imageCount/snapshotImages trong lock. Pom có `annotationProcessorPaths` (compiler plugin) + exclude lombok khỏi boot jar.
- **Log4j2 thay Logback** (2026-07-11): `spring-boot-starter-log4j2`; PHẢI exclude `spring-boot-starter-logging` khỏi TỪNG starter (webmvc, data-jpa, thymeleaf, websocket, json, webmvc-test) — sót một cái là Logback quay lại classpath. Chưa có `log4j2-spring.xml` (dùng default của Boot); thêm file đó nếu cần custom pattern/rolling file.
- **Handler injection**: `DeviceWebSocketHandler` `@Primary` extends `BaseWebSocketHandler` → `WebSocketConfig` tự chọn nó; **base không sửa**.
- **Key an toàn**: `DeviceKeyService` lưu **SHA-256 hex**, so sánh **constant-time** (`MessageDigest.isEqual`); 3 ô current/previous/pending → xoay không brick.
- **State machine**: `DeviceState` + `DeviceRuntime.lock` đã có sẵn khung cho Luồng 3/4 (mới dùng IDLE).
- **ddl-auto=update CHỈ áp lên bảng `devices`** (từ 2026-07-11): `DevicesOnlySchemaFilterProvider` (đăng ký qua `hibernate.hbm2ddl.schema_filter_provider`) chặn Hibernate create/update/validate 2 bảng của Python. Lý do (bug report backend 2026-07-11): Hibernate từng tạo `recognition_logs` thiếu `DEFAULT now()` → backend insert lỗi NOT NULL (đã hotfix `ALTER TABLE ... SET DEFAULT NOW()` trên RDS); và nếu DB trống Hibernate sẽ tạo `identities` thiếu cột `embedding VECTOR(512)` (entity không map). Schema 2 bảng đó do `db_utils.py` (backend) sở hữu tuyệt đối. Đã verify đủ 3 tiêu chí nghiệm thu: restart giữ default, insert kiểu backend OK, `/view/registers` 200.

---

## 9. Việc tiếp theo gợi ý

1. **Task P1 (Python, repo `3ddfa` của Khảm)** — BLOCKER Luồng 3: skill hoàn chỉnh để đưa cho Khảm/Claude Code nằm ở **`docs/SKILL_P1_Python_Backend.md`** (thêm `name` cho `/enroll`, ~3 dòng trong `api_server.py`, kèm hợp đồng API + checklist).
2. **Nginx EC2**: thêm location `/api/enroll/` → `proxy_pass http://view-service:8081; proxy_read_timeout 90s;` rồi `nginx -t && nginx -s reload` trong container.
3. **Verify cloud Luồng 3/4** (sau 1+2): script WS PowerShell như mục 7, thêm bước gửi `enroll_image`/`recognize_image`; xóa device + identity test sau khi xong.
4. Chốt auth cho `/api/enroll/start` (đang mở, xem mục CHƯA làm).
5. ~~UI web cho enroll~~ **ĐÃ XONG (2026-07-11)**: nút "Đăng ký khuôn mặt" trên `/view/registers` → modal chọn thiết bị online (`GET /api/enroll/devices` — nằm dưới `/api/enroll/` nên dùng chung location nginx) + nhập tên → gọi `/api/enroll/start`, hiển thị đủ các kết cục (ok/timeout/interrupted/disconnected/trùng tên/busy/offline), thành công thì reload trang.

---

## 10. Lưu ý bàn giao
- Toàn bộ Luồng 1/2/5 + docs **CHƯA commit** (working tree `feature/web-socket`). Base WS đã commit `fa2ed91`.
- Không commit `iot-ec2-key.pem` và giá trị `PROVISIONING_SECRET`.
- Người dùng giao tiếp bằng tiếng Việt; comment code cũng tiếng Việt (không dấu ở file .java để tránh lỗi encoding).
