# SPEC FIRMWARE ESP32-CAM — Hệ thống nhận diện khuôn mặt (bản đầy đủ, step-by-step)

> Tài liệu cho người (hoặc AI) viết firmware ESP32-CAM. **Toàn bộ protocol dưới đây đã được
> verify chạy thật end-to-end trên cloud ngày 2026-07-11** (13/13 test pass) bằng simulator
> `scripts/esp32-sim.ps1` — script đó chính là **reference implementation**: firmware làm đúng
> những gì script làm là chạy được. Khi nghi ngờ hành vi, đối chiếu với script.

---

## 0. Bức tranh tổng thể

```
[ESP32-CAM] --(1) HTTPS POST /device/register: xin apiKey (1 lần duy nhất)-->  ┐
[ESP32-CAM] --(2) WSS /ws: register -> online, heartbeat --------------------->│ iot-project.io.vn
[ESP32-CAM] <-(3) start_enroll <- server (admin bấm nút trên web) ------------│ (nginx TLS
[ESP32-CAM] --(3) enroll_image x5 ------------------------------------------->│  -> view-service)
[ESP32-CAM] --(4) recognize_image (người dùng BẤM NÚT vật lý) ---------------->│
[ESP32-CAM] <-(4) recognize_result <------------------------------------------│
[ESP32-CAM] <-(5) rotateKey / --(5) ackRotateKey ----------------------------->┘
```

Firmware có đúng **5 nhiệm vụ**, đánh số theo "luồng" (flow) của hệ thống:

| Luồng | Tên | Ai khởi phát | Kênh |
|---|---|---|---|
| 1 | Provisioning (xin apiKey lần đầu) | ESP32, khi NVS trống | HTTPS REST |
| 2 | Kết nối + đăng ký phiên + heartbeat | ESP32, mỗi lần kết nối | WSS |
| 3 | Enroll — chụp 5 ảnh đăng ký khuôn mặt | **Server** (admin bấm trên web) | WSS |
| 4 | Recognize — nhận diện | **ESP32** (nút bấm vật lý) | WSS |
| 5 | Xoay API key | Server (định kỳ) | WSS |

---

## 1. Hằng số hệ thống (copy nguyên vào firmware)

| Hằng số | Giá trị | Ghi chú |
|---|---|---|
| `HOST` | `iot-project.io.vn` | Không có port trong URL (nginx 443) |
| `REGISTER_URL` | `https://iot-project.io.vn/device/register` | Luồng 1 |
| `WS_HOST / WS_PORT / WS_PATH` | `iot-project.io.vn` / `443` / `/ws` | **Bắt buộc TLS (wss)** |
| `PROVISIONING_SECRET` | *hỏi người vận hành, nạp cứng* | Chỉ dùng cho Luồng 1 |
| `deviceId` | Chip ID từ eFuse MAC, 12 hex hoa | **Cố định phần cứng**, không đổi |
| `HEARTBEAT_MS` | `25000` (25s) | Giữ nhịp online |
| `RECONNECT_MS` | `5000` (5s) | WS đứt là phải tự nối lại |
| `ENROLL_COUNT` | server gửi trong lệnh (`count`, hiện = 5) | Đừng hardcode, đọc từ message |
| `ENROLL_WINDOW` | 30 giây cho **đủ 5 ảnh** | Quá hạn server hủy phiên |
| `MAX_IMG_B64` | `400000` ký tự | Ảnh base64 dài hơn bị server **bỏ qua im lặng** |
| Khung ảnh khuyến nghị | JPEG, **VGA (640x480)**, quality 12–15 | ~30–60KB → base64 ~40–80KB, an toàn dưới limit |
| `BUTTON_GPIO` | `13` (INPUT_PULLUP, nhấn = LOW) | Nút nhận diện; đổi tùy board |

**Phần cứng của dự án: chỉ gồm ESP32-CAM + 1 nút bấm.** Không có LED/relay/màn hình —
mọi phản hồi cho người dùng qua **Serial log** (khi debug) và **dashboard web** (vận hành thật:
kết quả nhận diện, lịch sử đều hiện ở `https://iot-project.io.vn/view/registers`).

Thư viện Arduino: `WiFi`, `WiFiClientSecure`, `HTTPClient`, **arduinoWebSockets (Links2004)**,
**ArduinoJson**, `Preferences` (NVS), `esp_camera`, `base64` (có sẵn trong ESP32 core).

> ⚠️ ESP32-CAM **phải có PSRAM bật** (board AI-Thinker có sẵn) — ảnh + chuỗi base64 + JSON
> chiếm ~200KB RAM lúc gửi. `fb_location = CAMERA_FB_IN_PSRAM`.

---

## 2. Dữ liệu lưu trữ (NVS)

Chỉ cần **một** giá trị bền vững:

| Key NVS | Kiểu | Ý nghĩa |
|---|---|---|
| `apiKey` | String | Key riêng của thiết bị, server cấp. Trống = chưa provisioning. |

**Quy tắc vàng (chống brick khi xoay key):** bất cứ khi nào nhận key mới (Luồng 1 hoặc 5) →
**GHI NVS TRƯỚC**, dùng key mới **SAU**. Và khi register luôn dùng **key mới nhất trong NVS**.
Server chấp nhận cả key cũ/mới trong lúc chuyển tiếp nên không bao giờ bị khóa ngoài.

---

## 3. State machine firmware (vòng đời chính)

```
        ┌────────┐  WiFi ok   ┌──────────────┐ NVS có key ┌────────────┐
BOOT ──>│ WIFI   ├───────────>│ PROVISION?   ├───────────>│ WS_CONNECT │<──────────┐
        │ connect│            │ (Luồng 1 nếu │            │ (wss /ws)  │           │
        └────────┘            │  NVS trống)  │            └─────┬──────┘           │
                              └──────────────┘                  │ connected        │
                                                                v                  │
                                                        ┌──────────────┐           │
                                                        │ REGISTERING  │ ack fail  │
                                                        │ gửi register ├── xóa NVS─┼─> PROVISION?
                                                        └─────┬────────┘           │
                                                              │ ack success        │ đứt kết nối
                                                              v                    │ (mọi lý do)
                                                        ┌──────────────┐           │
                                                        │   ONLINE     ├───────────┘
                                                        │ heartbeat 25s│   chờ 5s rồi reconnect
                                                        │ chờ lệnh WS  │
                                                        │ nghe nút bấm │
                                                        └──────────────┘
```

Trong `ONLINE` có 2 "chế độ con" tạm thời:
- `ENROLLING`: đang chụp-gửi loạt ảnh theo lệnh `start_enroll` (mục 6).
- Chờ `recognize_result` sau khi bấm nút (mục 7) — vẫn phải chạy `ws.loop()` đều.

**Bắt buộc**: server restart là mất session → firmware PHẢI có vòng lặp reconnect + register lại.
Không register lại = server coi là offline = không nhận được lệnh nào.

---

## 4. LUỒNG 1 — Provisioning (step-by-step)

Chạy **một lần duy nhất** khi NVS chưa có `apiKey` (máy mới hoặc sau factory-reset).

1. Đọc NVS key `apiKey`. Nếu **có** → bỏ qua toàn bộ mục này, sang Luồng 2.
2. Tính `deviceId` = eFuse MAC, format 12 hex hoa (ví dụ `A0B1C2D3E4F5`):
   ```cpp
   uint64_t mac = ESP.getEfuseMac();
   char id[13]; sprintf(id, "%012llX", mac);
   ```
3. Gửi:
   ```
   POST https://iot-project.io.vn/device/register
   Content-Type: application/json

   {"deviceId":"A0B1C2D3E4F5","secret":"<PROVISIONING_SECRET>"}
   ```
4. Xử lý response:

   | HTTP | Body | Firmware phải làm |
   |---|---|---|
   | 200 | `{"apiKey":"...","deviceId":"..."}` | **Ghi `apiKey` vào NVS NGAY** → sang Luồng 2 |
   | 401 | `{"error":"invalid secret"}` | Secret sai — lỗi cấu hình, in lỗi ra Serial và dừng (chỉ sửa được bằng nạp lại firmware) |
   | 429 | `{"error":"too many requests"}` | Bị rate-limit → đợi 30–60s rồi thử lại |
   | khác / timeout | — | Đợi 5s, thử lại (vòng lặp vô hạn có backoff) |

5. Gọi lại nhiều lần **không sao** (idempotent theo deviceId). Sau factory-reset gọi lại sẽ được
   cấp key mới, key cũ tự vô hiệu dần — không cần xử lý gì thêm.

---

## 5. LUỒNG 2 — Kết nối WSS + register + heartbeat (step-by-step)

1. Mở WebSocket TLS: `wss://iot-project.io.vn:443/ws`
   (arduinoWebSockets: `ws.beginSSL(HOST, 443, "/ws")`; dev có thể `setInsecure()`, sản phẩm nạp CA thật).
2. **Ngay khi** sự kiện `WStype_CONNECTED` → gửi:
   ```json
   {"type":"register","deviceId":"A0B1C2D3E4F5","apiKey":"<apiKey trong NVS>"}
   ```
3. Chờ phản hồi:
   - `{"type":"register_ack","status":"success","deviceId":"..."}` → **ONLINE**. Từ giờ mới được gửi ảnh.
   - `{"type":"register_ack","status":"fail",...}` → server sẽ đóng socket (code 1008).
     Nguyên nhân: key sai/không tồn tại → **xóa `apiKey` khỏi NVS**, quay về Luồng 1.
4. Khi ONLINE, mỗi **25 giây** gửi heartbeat (server không trả lời — không chờ response):
   ```json
   {"type":"heartbeat","deviceId":"A0B1C2D3E4F5"}
   ```
5. Nếu gửi bất kỳ message nghiệp vụ nào **trước khi** register thành công, server trả
   `{"type":"error","reason":"not_registered"}` — nếu thấy message này, quay lại bước 2.
6. Mất kết nối (`WStype_DISCONNECTED`) vì bất kỳ lý do gì → đợi 5s → mở lại từ bước 1
   (arduinoWebSockets: `ws.setReconnectInterval(5000)` tự làm; chỉ cần bảo đảm bước 2 chạy lại mỗi lần CONNECTED).

> Debug nhanh: gửi `{"type":"ping"}` → nhận `{"type":"pong","ts":<epoch ms>}`.

---

## 6. LUỒNG 3 — Enroll: server ra lệnh, ESP32 chụp N ảnh (step-by-step)

Bối cảnh: admin mở dashboard web, chọn thiết bị này, nhập tên, bấm "Bắt đầu chụp".
Phía firmware chỉ thấy một message đến:

```json
{"type":"start_enroll","sessionId":"c71590bf-22b5-4088-bf45-10eedf2cbf7a","count":5}
```

Firmware xử lý:

1. Lưu `sessionId` (chuỗi, gửi lại **nguyên văn** trong từng ảnh) và `count` (đọc từ message, đừng hardcode).
2. Xóa cờ `stopEnroll = false`.
3. Lặp `i = 1..count` — **QUAN TRỌNG: xử lý TỪNG ảnh một, trong RAM không bao giờ có quá 1 ảnh.**
   Tuyệt đối KHÔNG chụp cả 5 ảnh rồi gửi dồn (5 ảnh + 5 chuỗi base64 ≈ 600KB–1MB → hết RAM, reset giữa chừng).
   Chu trình mỗi ảnh: **chụp → encode → trả frame buffer NGAY → gửi → giải phóng chuỗi → mới chụp ảnh kế**:
   a. Nếu `stopEnroll == true` → **dừng ngay**, thoát vòng lặp (xem bước 5).
   b. Chụp 1 ảnh JPEG (VGA, quality 12–15) → encode base64 → gọi `esp_camera_fb_return(fb)` **ngay sau khi encode xong** (trả frame buffer cho driver, không giữ).
   c. Nếu chuỗi base64 > 400.000 ký tự → giảm quality/size rồi chụp lại (server bỏ qua ảnh quá to, **không báo lỗi**).
   d. Gửi (một text frame duy nhất):
      ```json
      {"type":"enroll_image","sessionId":"<nguyên văn>","deviceId":"A0B1C2D3E4F5","image":"<base64>"}
      ```
      Gửi xong, để chuỗi base64 ra khỏi scope (hoặc `b64 = ""`) để giải phóng RAM **trước khi** chụp ảnh tiếp theo.
   e. `ws.loop()` + delay 300–500ms giữa các ảnh (để người dùng kịp đổi góc mặt nhẹ,
      và để nhận được `stop_enroll` nếu có). **Toàn bộ `count` ảnh phải gửi xong trong 30 giây.**
   > Server **gom và đếm ảnh ở phía server** theo `sessionId` — thiết bị không cần "gói 5 ảnh thành 1 request",
   > cứ bắn lần lượt 5 message rời là đúng protocol (simulator cũng làm y vậy).
4. Không có phản hồi per-ảnh. Kết quả enroll trả cho **web**, không trả cho thiết bị. Gửi xong ảnh cuối → coi như xong, quay lại chế độ chờ.
5. Bất cứ lúc nào nhận:
   ```json
   {"type":"stop_enroll","sessionId":"..."}
   ```
   → set `stopEnroll = true`. Nghĩa là phiên đã bị hủy (hết 30s, hoặc bị nhận diện chen ngang).
   Ảnh lỡ gửi thêm với sessionId cũ **vô hại** — server bỏ qua im lặng.

Sai lầm cần tránh:
- ❌ Tự bịa sessionId hoặc gửi sessionId cũ — ảnh sẽ bị bỏ qua, web báo timeout.
- ❌ Chụp cả 5 ảnh trước rồi gửi dồn — dễ hết RAM; chụp-gửi-xóa từng ảnh một.
- ❌ Block cứng không gọi `ws.loop()` trong lúc chụp — sẽ không bao giờ thấy `stop_enroll` và heartbeat bị nghẽn.

**Hỏi hay gặp: đang chờ `recognize_result` mà `start_enroll` tới thì sao?**
Không xảy ra theo thiết kế: khi thiết bị đang nhận diện, server trả `409 device busy` cho web
và KHÔNG gửi `start_enroll`. Message trên 1 kết nối WS đến đúng thứ tự, và server chỉ gửi
`start_enroll` sau khi đã gửi `recognize_result` — nên nếu firmware thấy `start_enroll` thì
result chắc chắn đã đến trước đó. Phòng thủ duy nhất cần có: trong handler `start_enroll`,
clear cờ đang-chờ-result (`waitingResult = false`) rồi chạy enroll bình thường — che nốt
trường hợp result thất lạc vì rớt mạng đúng khoảnh khắc đó.

---

## 7. LUỒNG 4 — Recognize: nút bấm vật lý (step-by-step)

1. Cấu hình `BUTTON_GPIO` = INPUT_PULLUP. Nhấn = LOW. **Debounce 50ms** + chặn nhấn lặp:
   sau khi gửi 1 ảnh, không nhận nhấn mới cho tới khi có `recognize_result` hoặc quá 15s.
2. Khi nhấn hợp lệ (và đang ONLINE):
   a. Chụp 1 ảnh JPEG (thông số như enroll).
   b. Encode base64 (limit 400k như trên).
   c. Gửi:
      ```json
      {"type":"recognize_image","deviceId":"A0B1C2D3E4F5","image":"<base64>"}
      ```
3. Chờ (tiếp tục `ws.loop()`, thường 1–5 giây) một trong các message:

   | Message nhận được | Ý nghĩa | Gợi ý UX |
   |---|---|---|
   | `{"type":"recognize_result","status":"ok","identity":"Nguyen Van A","confidence":0.99}` | Khớp | In tên + confidence ra Serial; kết quả cũng hiện trên dashboard web (lịch sử nhận diện) |
   | `{"type":"recognize_result","status":"unknown","confidence":0.41}` | Không khớp ai | In "khong khop" ra Serial |
   | `{"type":"recognize_result","status":"error","reason":"..."}` | Lỗi hệ thống (backend down/ảnh hỏng) | In lỗi ra Serial, cho phép bấm lại |
   | `{"type":"busy","reason":"recognizing"}` | Ảnh trước còn đang xử lý (bấm dồn) | Bỏ qua, chờ result |
   | `{"type":"busy","reason":"enrolling"}` | Enroll vừa đủ ảnh, server đang xử lý | Chờ vài giây bấm lại |

4. Trường hợp đặc biệt: bấm nút **giữa lúc đang chụp enroll** → recognize được ưu tiên,
   server tự hủy enroll và gửi `stop_enroll` — firmware chỉ cần xử lý `stop_enroll` như mục 6.5, không cần logic riêng.

---

## 7b. Xung đột enroll ↔ recognize — thiết bị KHÔNG phải phân xử

Mọi quyết định tranh chấp nằm ở **server** (state machine loại trừ theo từng thiết bị).
Firmware chỉ việc phản ứng theo message nhận được:

| Tình huống | Ai xử lý | Thiết bị thấy gì / phải làm gì |
|---|---|---|
| Đang **recognize** → admin bấm enroll trên web | **Server chặn** — trả `409 device busy` cho web | Không thấy gì. `start_enroll` không bao giờ tới trong lúc này (message trên 1 kết nối WS đến đúng thứ tự, server chỉ gửi `start_enroll` sau khi đã gửi `recognize_result`) |
| Đang **enroll** (chụp dở) → người dùng bấm nút recognize | **Server preempt** — recognize được ưu tiên, hủy enroll | Nhận `stop_enroll` → dừng chụp (mục 6.5); sau đó nhận `recognize_result` bình thường |
| Enroll **đã đủ ảnh**, backend đang xử lý → bấm nút | Server từ chối ảnh nhận diện | Nhận `{"type":"busy","reason":"enrolling"}` → chờ vài giây bấm lại |
| Đang recognize → bấm nút thêm lần nữa (bấm dồn) | Server từ chối | Nhận `{"type":"busy","reason":"recognizing"}` → chờ result rồi mới bấm |

Phòng thủ duy nhất phía firmware: trong handler `start_enroll` clear cờ đang-chờ-result
(`waitingResult = false`) — che trường hợp `recognize_result` thất lạc vì rớt mạng (code mẫu mục 10 đã có).

---

## 7c. "Trạng thái thiết bị" — ai giữ gì? (firmware KHÔNG gửi status)

Có 3 tầng "status" khác nhau — đừng nhầm lẫn, và đừng tự chế message báo trạng thái:

| Tầng | Giá trị | Ai set | Firmware phải làm gì |
|---|---|---|---|
| `devices.status` (DB) | `online` / `offline` | **Server tự set**: `register` thành công → online; rớt WS → offline; heartbeat giữ online + cập nhật `last_seen` | Chỉ cần register + heartbeat đều đặn. KHÔNG có giá trị `recognizing`/`enrolling` trong DB — trạng thái phiên không lưu DB (thiết kế đã chốt) |
| `DeviceState` (RAM server) | `IDLE` / `RECOGNIZING` / `ENROLLING` | **Server tự chuyển** theo message: `recognize_image` → RECOGNIZING → (gửi result xong) → IDLE; enroll tương tự | Không làm gì — server tự suy ra từ message thiết bị gửi, không có field status nào để gửi kèm |
| Cờ cục bộ firmware | `online`, `waitingResult`, `stopEnroll` | **Firmware tự giữ** cho logic của chính nó | Như code mẫu mục 10. Chỉ sống trong RAM thiết bị, không gửi lên server |

Ví dụ với tình huống mục 7b (đang recognize, web bấm enroll): server giữ nguyên `RECOGNIZING`,
trả 409 cho web, xong recognize thì tự về `IDLE` — **không tầng nào cần firmware can thiệp**.

---

## 8. LUỒNG 5 — Xoay key (server chủ động, step-by-step)

Thi thoảng (key > 24h tuổi) server gửi:

```json
{"type":"rotateKey","deviceId":"A0B1C2D3E4F5","newApiKey":"xyz...","timestamp":"2026-07-11T15:30:00Z"}
```

Firmware — **thứ tự 3 bước này là bất di bất dịch**:

1. **Ghi `newApiKey` vào NVS** (đè key cũ).
2. Cập nhật biến `apiKey` trong RAM = key mới.
3. Gửi xác nhận:
   ```json
   {"type":"ackRotateKey","deviceId":"A0B1C2D3E4F5","status":"success","timestamp":"<ISO-8601 hoặc chuỗi rỗng>"}
   ```

Nếu ack bị mất (rớt mạng ngay sau khi ghi NVS): **không cần làm gì** — lần reconnect sau
register bằng key mới trong NVS, server nhận ra và tự hoàn tất. Đây là lý do của quy tắc vàng mục 2.

> Chú ý: message xoay key dùng **camelCase** (`rotateKey`, `ackRotateKey`, `newApiKey`) —
> khác các message còn lại (snake_case). Đừng "sửa cho đồng bộ".

---

## 9. Bảng message đầy đủ (nguồn sự thật duy nhất)

```
# Thiết bị -> Server
{"type":"register",        "deviceId":"...", "apiKey":"..."}
{"type":"heartbeat",       "deviceId":"..."}
{"type":"enroll_image",    "sessionId":"...", "deviceId":"...", "image":"<base64>"}
{"type":"recognize_image", "deviceId":"...", "image":"<base64>"}
{"type":"ackRotateKey",    "deviceId":"...", "status":"success", "timestamp":"..."}
{"type":"ping"}                                                        # debug

# Server -> Thiết bị
{"type":"register_ack",     "status":"success|fail", "deviceId":"..."}
{"type":"start_enroll",     "sessionId":"...", "count":5}
{"type":"stop_enroll",      "sessionId":"..."}
{"type":"recognize_result", "status":"ok",      "identity":"...", "confidence":0.99}
{"type":"recognize_result", "status":"unknown", "confidence":0.41}
{"type":"recognize_result", "status":"error",   "reason":"..."}
{"type":"busy",             "reason":"recognizing|enrolling"}
{"type":"rotateKey",        "deviceId":"...", "newApiKey":"...", "timestamp":"..."}
{"type":"pong",             "ts":1720000000000}                        # debug
{"type":"error",            "reason":"not_registered|invalid_json|unknown_type:<t>"}
```

---

## 10. Code Arduino đầy đủ (khung hoàn chỉnh, AI-Thinker ESP32-CAM)

> Khung này chạy được cả 5 luồng. Chỗ nào cần chỉnh theo board thực tế đã đánh dấu `// TODO`.

```cpp
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebSocketsClient.h>   // arduinoWebSockets (Links2004)
#include <ArduinoJson.h>
#include <Preferences.h>
#include <base64.h>
#include "esp_camera.h"

// ================== CẤU HÌNH ==================
static const char* WIFI_SSID   = "...";                 // TODO
static const char* WIFI_PASS   = "...";                 // TODO
static const char* HOST        = "iot-project.io.vn";
static const char* SECRET      = "<PROVISIONING_SECRET>"; // TODO: hỏi người vận hành
static const int   BUTTON_GPIO = 13;                    // TODO: theo mạch thật
static const size_t MAX_IMG_B64 = 400000;

// Pin map AI-Thinker ESP32-CAM
#define PWDN_GPIO 32
#define RESET_GPIO -1
#define XCLK_GPIO 0
#define SIOD_GPIO 26
#define SIOC_GPIO 27
#define Y9 35
#define Y8 34
#define Y7 39
#define Y6 36
#define Y5 21
#define Y4 19
#define Y3 18
#define Y2 5
#define VSYNC_GPIO 25
#define HREF_GPIO 23
#define PCLK_GPIO 22

String deviceId, apiKey;
Preferences nvs;
WebSocketsClient ws;
bool online = false;            // đã register_ack success chưa
volatile bool stopEnroll = false;
bool waitingResult = false;     // đang chờ recognize_result (chặn bấm dồn)
unsigned long waitingSince = 0, lastBeat = 0, lastBtn = 0;

// ================== TIỆN ÍCH ==================
String hwDeviceId() {
  uint64_t mac = ESP.getEfuseMac();
  char b[13]; sprintf(b, "%012llX", mac);
  return String(b);
}

bool initCamera() {
  camera_config_t c = {};
  // LEDC o day KHONG phai den LED — la ngoai vi phat xung clock cho camera. DUNG XOA.
  c.ledc_channel = LEDC_CHANNEL_0; c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0=Y2; c.pin_d1=Y3; c.pin_d2=Y4; c.pin_d3=Y5; c.pin_d4=Y6; c.pin_d5=Y7; c.pin_d6=Y8; c.pin_d7=Y9;
  c.pin_xclk=XCLK_GPIO; c.pin_pclk=PCLK_GPIO; c.pin_vsync=VSYNC_GPIO; c.pin_href=HREF_GPIO;
  c.pin_sccb_sda=SIOD_GPIO; c.pin_sccb_scl=SIOC_GPIO; c.pin_pwdn=PWDN_GPIO; c.pin_reset=RESET_GPIO;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size   = FRAMESIZE_VGA;      // 640x480 — khớp giới hạn hệ thống
  c.jpeg_quality = 14;                 // 12–15; số to = nén mạnh = file nhỏ
  c.fb_count     = 1;
  c.fb_location  = CAMERA_FB_IN_PSRAM; // BẮT BUỘC có PSRAM
  return esp_camera_init(&c) == ESP_OK;
}

// Chụp 1 ảnh -> base64. Trả chuỗi rỗng nếu lỗi/quá to.
String captureB64() {
  camera_fb_t* fb = esp_camera_fb_get();  // xả 1 frame cũ (exposure)
  if (fb) { esp_camera_fb_return(fb); }
  fb = esp_camera_fb_get();
  if (!fb) return "";
  String b64 = base64::encode(fb->buf, fb->len);
  esp_camera_fb_return(fb);
  if (b64.length() > MAX_IMG_B64) { Serial.println("Anh qua to, giam quality!"); return ""; }
  return b64;
}

// Gửi message chứa ảnh: ghép chuỗi thủ công (base64 không cần escape JSON)
void sendImageMsg(const char* type, const String& sessionId, const String& b64) {
  String msg; msg.reserve(b64.length() + 160);
  msg  = "{\"type\":\""; msg += type; msg += "\"";
  if (sessionId.length()) { msg += ",\"sessionId\":\""; msg += sessionId; msg += "\""; }
  msg += ",\"deviceId\":\""; msg += deviceId; msg += "\",\"image\":\""; msg += b64; msg += "\"}";
  ws.sendTXT(msg);
}

void sendJson(JsonDocument& d) { String s; serializeJson(d, s); ws.sendTXT(s); }

// ================== LUỒNG 1 ==================
bool provisionIfNeeded() {
  apiKey = nvs.getString("apiKey", "");
  if (apiKey.length() > 0) return true;

  WiFiClientSecure cli; cli.setInsecure();          // TODO sản phẩm: nạp CA thật
  HTTPClient http;
  http.begin(cli, String("https://") + HOST + "/device/register");
  http.addHeader("Content-Type", "application/json");
  String body = "{\"deviceId\":\"" + deviceId + "\",\"secret\":\"" + SECRET + "\"}";
  int code = http.POST(body);
  if (code == 200) {
    JsonDocument d; deserializeJson(d, http.getString());
    apiKey = d["apiKey"].as<String>();
    nvs.putString("apiKey", apiKey);                // GHI NVS TRƯỚC
    http.end();
    Serial.println("Luong 1 OK: da co apiKey");
    return true;
  }
  Serial.printf("Provision HTTP %d\n", code);
  http.end();
  return false;                                     // 401: sai secret | 429: đợi lâu hơn
}

// ================== LUỒNG 3 ==================
// GUI TUNG ANH MOT: moi vong lap chi giu 1 anh trong RAM (chup -> gui -> giai phong -> chup tiep).
// KHONG gom 5 anh roi gui don — se het RAM. Server tu dem du `count` anh theo sessionId.
void doEnroll(const String& sessionId, int count) {
  Serial.printf("start_enroll: can %d anh\n", count);
  stopEnroll = false;
  for (int i = 1; i <= count; i++) {
    if (stopEnroll) { Serial.println("Dung theo stop_enroll"); return; }
    {   // block scope: b64 + msg duoc giai phong ngay khi ra khoi {} — truoc lan chup ke tiep
      String b64 = captureB64();                 // frame buffer da duoc tra lai ben trong ham nay
      if (b64.length()) {
        sendImageMsg("enroll_image", sessionId, b64);
        Serial.printf("  da gui anh %d/%d (RAM free: %u)\n", i, count, ESP.getFreeHeap());
      }
    }   // <- b64 chet o day, RAM ve lai muc cu
    // 400ms giữa các ảnh + vẫn bơm WS để nhận stop_enroll
    for (int t = 0; t < 8; t++) { ws.loop(); delay(50); }
  }
}

// ================== XỬ LÝ MESSAGE ==================
void onWsEvent(WStype_t type, uint8_t* payload, size_t len) {
  if (type == WStype_CONNECTED) {                   // Luồng 2 bước 2
    JsonDocument d;
    d["type"]="register"; d["deviceId"]=deviceId; d["apiKey"]=apiKey;
    sendJson(d);
    return;
  }
  if (type == WStype_DISCONNECTED) { online = false; return; }
  if (type != WStype_TEXT) return;

  JsonDocument d;
  if (deserializeJson(d, payload, len)) return;
  String t = d["type"] | "";

  if (t == "register_ack") {                        // Luồng 2 bước 3
    if (String(d["status"] | "") == "success") { online = true; Serial.println("ONLINE"); }
    else { nvs.remove("apiKey"); apiKey = ""; online = false; ws.disconnect(); } // về Luồng 1
  }
  else if (t == "start_enroll") {                   // Luồng 3
    waitingResult = false;  // phong thu: server chi gui start_enroll khi da xong nhan dien
    doEnroll(d["sessionId"].as<String>(), d["count"] | 5);
  }
  else if (t == "stop_enroll") {                    // Luồng 3 bước 5
    stopEnroll = true;
  }
  else if (t == "recognize_result") {               // Luồng 4 bước 3
    waitingResult = false;
    String st = d["status"] | "";
    if (st == "ok")      Serial.printf("KHOP: %s (%.4f)\n", (const char*)d["identity"], (double)d["confidence"]);
    else if (st == "unknown") Serial.println("Khong khop ai");
    else                 Serial.printf("Loi nhan dien: %s\n", (const char*)d["reason"]);
    // Phan cung chi co nut + cam: ket qua in Serial la du, dashboard web hien lich su day du
  }
  else if (t == "busy") {                           // bấm dồn / enroll đang xử lý
    Serial.printf("busy: %s\n", (const char*)d["reason"]);
  }
  else if (t == "rotateKey") {                      // Luồng 5 — thứ tự bất di bất dịch
    String newKey = d["newApiKey"].as<String>();
    nvs.putString("apiKey", newKey);                // 1. NVS trước
    apiKey = newKey;                                // 2. RAM sau
    JsonDocument a;                                 // 3. ack cuối
    a["type"]="ackRotateKey"; a["deviceId"]=deviceId; a["status"]="success"; a["timestamp"]="";
    sendJson(a);
    Serial.println("Da xoay key");
  }
  else if (t == "error") {
    Serial.printf("Server error: %s\n", (const char*)d["reason"]);
  }
}

// ================== SETUP / LOOP ==================
void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_GPIO, INPUT_PULLUP);
  nvs.begin("dev", false);
  deviceId = hwDeviceId();

  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) delay(500);

  if (!initCamera()) { Serial.println("Camera FAIL"); while (true) delay(1000); }

  while (!provisionIfNeeded()) delay(5000);         // Luồng 1

  ws.beginSSL(HOST, 443, "/ws");                    // Luồng 2 bước 1 (wss)
  ws.onEvent(onWsEvent);
  ws.setReconnectInterval(5000);                    // Luồng 2 bước 6
}

void loop() {
  ws.loop();

  // Heartbeat 25s (Luồng 2 bước 4)
  if (online && millis() - lastBeat > 25000) {
    lastBeat = millis();
    JsonDocument d; d["type"]="heartbeat"; d["deviceId"]=deviceId;
    sendJson(d);
  }

  // Nút nhận diện (Luồng 4) — debounce 50ms + chặn bấm dồn 15s
  if (online && digitalRead(BUTTON_GPIO) == LOW && millis() - lastBtn > 50) {
    lastBtn = millis();
    if (waitingResult && millis() - waitingSince < 15000) return; // đang chờ result
    String b64 = captureB64();
    if (b64.length()) {
      sendImageMsg("recognize_image", "", b64);
      waitingResult = true; waitingSince = millis();
      Serial.println("Da gui recognize_image, cho ket qua...");
    }
  }
}
```

---

## 11. Trình tự tự test firmware (làm đúng thứ tự, mỗi bước pass mới sang bước sau)

Server cloud đang chạy sẵn — không cần dựng gì thêm. Mở dashboard
`https://iot-project.io.vn/view/registers` để quan sát.

1. **Test Luồng 1**: nạp firmware, xem Serial log `Luong 1 OK`. Kiểm tra chéo: nhờ người vận hành xem bảng `devices` trên RDS có deviceId của bạn.
2. **Test Luồng 2**: log `ONLINE`. Kiểm tra chéo: trên dashboard bấm "Đăng ký khuôn mặt" → dropdown thiết bị phải hiện deviceId của bạn.
3. **Test Luồng 3**: trên dashboard chọn thiết bị + nhập tên + "Bắt đầu chụp" → Serial phải in `start_enroll` rồi `da gui anh 1/5..5/5`; web hiện "Đăng ký thành công"; tên xuất hiện trong danh sách định danh.
4. **Test Luồng 4**: bấm nút vật lý → Serial in `KHOP: <tên> (0.99xx)`. Vào chi tiết định danh trên web thấy thêm 1 dòng lịch sử nhận diện.
5. **Test timeout Luồng 3**: rút điện giữa lúc chụp / che camera cho ảnh lỗi → web phải hiện timeout, thiết bị nhận `stop_enroll` (nếu còn kết nối).
6. **Test reconnect**: tắt WiFi router 10s rồi bật → thiết bị phải tự ONLINE lại không cần reset.
7. **Luồng 5** xảy ra tự động khi key >24h — không test tay được; chỉ cần code đúng mục 8. (Đã được verify sẵn phía server.)

**Mẹo debug không cần phần cứng:** chạy `scripts\esp32-sim.ps1` (PowerShell, trong repo view-service)
song song để so sánh — sim là thiết bị "chuẩn"; nếu sim làm được mà firmware không được thì khác biệt nằm ở firmware.

---

## 12. Lỗi thường gặp & cách nhận biết

| Triệu chứng | Nguyên nhân khả dĩ | Fix |
|---|---|---|
| register REST trả 401 | Secret sai / có khoảng trắng thừa | Đối chiếu secret với người vận hành |
| `register_ack fail` liên tục | apiKey trong NVS cũ/hỏng | Xóa NVS `apiKey` → provisioning lại (code đã tự làm) |
| Gửi ảnh không thấy gì xảy ra, web timeout | Ảnh base64 > 400k chars (server bỏ qua im lặng) | Giảm frame size/quality; log độ dài chuỗi trước khi gửi |
| Web timeout dù đã gửi đủ ảnh | sessionId gửi sai/tự bịa | Gửi lại nguyên văn sessionId từ `start_enroll` |
| `{"type":"error","reason":"not_registered"}` | Gửi ảnh trước khi có `register_ack success` | Chỉ gửi khi cờ `online == true` |
| Crash/reset khi chụp | Hết RAM (không PSRAM / fb_count lớn / giữ nhiều ảnh) | PSRAM bật, `fb_count=1`, chụp-gửi-xóa từng ảnh |
| WSS không kết nối được | Quên TLS (dùng `begin` thay `beginSSL`) | `beginSSL(HOST, 443, "/ws")` |
| Camera không init sau khi "dọn code LED" | Xóa nhầm `c.ledc_channel`/`c.ledc_timer` (tưởng là LED) | Khôi phục — LEDC là ngoại vi xung clock camera, không phải đèn |
| Nhận diện lúc được lúc `busy` | Bấm nút dồn dập | Chặn bấm mới tới khi có result (code mẫu đã có) |
