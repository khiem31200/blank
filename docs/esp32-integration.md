# Hand-down: Tích hợp ESP32-CAM với view-service

> Phạm vi: **chỉ các chức năng ĐÃ triển khai & test** ở view-service:
> **Luồng 1** (đăng ký thiết bị REST) · **Luồng 2** (mở WebSocket + register + heartbeat) · **Luồng 5** (xoay API key).
> Chưa có (đừng dựa vào): enroll khuôn mặt, recognize. Các message `start_enroll`/`enroll_image`/`recognize_image`/`recognize_result` **chưa được server xử lý**.

Base URL: `https://iot-project.io.vn` · WebSocket: `wss://iot-project.io.vn/ws` (TLS bắt buộc).

---

## 0. Khái niệm cố định

| Thứ | Giá trị / quy tắc |
|-----|-------------------|
| `deviceId` | Định danh phần cứng **cố định** (chip id hoặc eFuse MAC). Chọn **một** loại và giữ nguyên mãi. |
| `PROVISIONING_SECRET` | Secret chung nạp cứng vào firmware, dùng **một lần** để xin key. Giá trị hiện tại do backend cấp (hỏi người vận hành). |
| `apiKey` | Key riêng của thiết bị, server cấp. **Lưu vào NVS**, dùng để mở WS. |
| Lưu trữ | Dùng `Preferences` (NVS): tối thiểu lưu `apiKey`. |

Server chỉ lưu **hash** của key và so sánh 3 ô `current / previous / pending` → trong lúc xoay key, cả key cũ lẫn mới đều xác thực được (không sợ brick).

---

## 1. Luồng 1 — Đăng ký thiết bị (REST, một lần khi NVS trống)

Khi khởi động: nếu NVS **đã có** `apiKey` → bỏ qua, sang mục 2. Nếu **trống**:

```
POST https://iot-project.io.vn/device/register
Content-Type: application/json

{ "deviceId": "<chipid>", "secret": "<PROVISIONING_SECRET>" }
```

Phản hồi:

| HTTP | Body | Ý nghĩa |
|------|------|---------|
| `200` | `{"apiKey":"...","deviceId":"..."}` | Thành công → **ghi `apiKey` vào NVS TRƯỚC**, rồi mở WS |
| `401` | `{"error":"invalid secret"}` | Sai secret |
| `400` | `{"error":"deviceId required"}` | Thiếu deviceId |
| `429` | `{"error":"too many requests"}` | Bị rate-limit (theo IP+deviceId) → chờ rồi thử lại |

- **Idempotent** theo `deviceId`: gọi lại không tạo bản ghi trùng. Gọi lại (sau factory reset) sẽ **cấp key mới** và dời key cũ sang `previous`.
- Quy tắc firmware: **ghi NVS xong mới** chuyển sang mở WS.

---

## 2. Luồng 2 — Mở WebSocket + register + heartbeat

1. Mở `wss://iot-project.io.vn/ws`.
2. Gửi ngay:
   ```json
   { "type": "register", "deviceId": "<chipid>", "apiKey": "<apiKey trong NVS>" }
   ```
3. Nhận:
   - Thành công: `{ "type":"register_ack", "status":"success", "deviceId":"..." }` → coi như online.
   - Thất bại: `{ "type":"register_ack", "status":"fail", ... }` → **server đóng kết nối** (close code `1008` PolicyViolation). Thường do `apiKey` sai → nên xoá NVS và quay lại Luồng 1.
4. **Heartbeat** định kỳ (khuyến nghị 20–30s):
   ```json
   { "type": "heartbeat", "deviceId": "<chipid>" }
   ```
   Server cập nhật `last_seen`, **không phản hồi**. Nếu gửi khi chưa register → `{ "type":"error", "reason":"not_registered" }`.
5. Mất kết nối / server đóng → server tự đánh dấu offline. **Firmware bắt buộc có vòng lặp reconnect** (server restart = mất mọi session in-memory → phải register lại).

> Tiện debug: gửi `{ "type":"ping" }` → nhận `{ "type":"pong", "ts":<epoch ms> }`. Không cần cho vận hành.

---

## 3. Luồng 5 — Xoay API key (server chủ động)

Server tự động (theo lịch, khi key quá tuổi) gửi xuống thiết bị **đang online**:

```json
{
  "type": "rotateKey",
  "deviceId": "esp32cam-123456",
  "newApiKey": "xyz789",
  "timestamp": "2026-07-09T15:30:00Z"
}
```

Xử lý ở firmware — **thứ tự quan trọng để không brick**:
1. **Ghi `newApiKey` vào NVS TRƯỚC** (đè `apiKey` cũ), rồi dùng `newApiKey` làm key hiện hành.
2. Gửi xác nhận:
   ```json
   {
     "type": "ackRotateKey",
     "deviceId": "esp32cam-123456",
     "status": "success",
     "timestamp": "2026-07-09T15:30:05Z"
   }
   ```
   Server chỉ promote khi `status == "success"`.

Phục hồi khi ack thất lạc:
- Nếu ack không tới server (mất mạng ngay sau khi đổi), **không sao**: lần kết nối sau chỉ cần `register` bằng **key mới trong NVS**. Server nhận ra key này là `pending` và **tự promote** → `register_ack success`.
- ⇒ Quy tắc vàng: **luôn `register` bằng key MỚI NHẤT đang lưu trong NVS.** Không cần logic gì thêm.

Vì server chấp nhận cả `current/previous/pending`, kể cả khi bạn chưa kịp đổi mà reconnect bằng key cũ vẫn vào được.

---

## 4. Checklist firmware

- [ ] `deviceId` = định danh phần cứng cố định (chốt chip id **hoặc** MAC).
- [ ] Nạp cứng `PROVISIONING_SECRET`; chỉ dùng cho `/device/register` khi NVS trống.
- [ ] `/device/register` 200 → **ghi `apiKey` vào NVS trước** khi mở WS.
- [ ] Mở `wss` (TLS) → gửi `register` → chờ `register_ack success`.
- [ ] `register_ack fail` → xoá NVS `apiKey`, quay lại Luồng 1.
- [ ] Gửi `heartbeat` mỗi ~20–30s.
- [ ] Vòng lặp **reconnect có backoff**; sau reconnect luôn `register` lại.
- [ ] Nhận `rotateKey` → **ghi NVS trước**, rồi gửi `ackRotateKey` (`status:"success"`).
- [ ] Luôn `register` bằng key mới nhất trong NVS (đảm bảo phục hồi khi ack mất).

---

## 5. Bảng message (chỉ phần đã có)

```
# Thiet bi -> Server
{ "type":"register",     "deviceId":"...", "apiKey":"..." }
{ "type":"heartbeat",    "deviceId":"..." }
{ "type":"ackRotateKey", "deviceId":"...", "status":"success", "timestamp":"<ISO-8601 UTC>" }
{ "type":"ping" }                                   # debug

# Server -> Thiet bi
{ "type":"register_ack", "status":"success|fail", "deviceId":"..." }
{ "type":"rotateKey",    "deviceId":"...", "newApiKey":"...", "timestamp":"<ISO-8601 UTC>" }
{ "type":"pong",  "ts":<epoch ms> }                 # debug
{ "type":"error", "reason":"not_registered | invalid_json | unknown_type:<t>" }
```

---

## 6. Khung Arduino (ESP32-CAM) tham khảo

Thư viện gợi ý: **arduinoWebSockets** (Links2004), **ArduinoJson**, **Preferences** (NVS), **HTTPClient + WiFiClientSecure**.

```cpp
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>

static const char* HOST   = "iot-project.io.vn";
static const char* SECRET = "<PROVISIONING_SECRET>";     // nap cung
String  deviceId;                                        // = chip id / MAC
String  apiKey;
Preferences nvs;
WebSocketsClient ws;
unsigned long lastBeat = 0;

String hwDeviceId() {                                    // chot MOT loai va giu nguyen
  uint64_t mac = ESP.getEfuseMac();
  char b[13]; sprintf(b, "%012llX", mac); return String(b);
}

// --- Luong 1: xin apiKey neu NVS trong ---
bool provisionIfNeeded() {
  nvs.begin("dev", false);
  apiKey = nvs.getString("apiKey", "");
  if (apiKey.length() > 0) return true;

  WiFiClientSecure cli; cli.setInsecure();               // hoac nap CA that
  HTTPClient http; http.begin(cli, String("https://") + HOST + "/device/register");
  http.addHeader("Content-Type", "application/json");
  String body = "{\"deviceId\":\"" + deviceId + "\",\"secret\":\"" + SECRET + "\"}";
  int code = http.POST(body);
  if (code == 200) {
    JsonDocument d; deserializeJson(d, http.getString());
    apiKey = d["apiKey"].as<String>();
    nvs.putString("apiKey", apiKey);                     // GHI NVS TRUOC
    http.end(); return true;
  }
  http.end(); return false;                              // 401/429 -> retry sau
}

void sendRegister() {
  JsonDocument d; d["type"]="register"; d["deviceId"]=deviceId; d["apiKey"]=apiKey;
  String s; serializeJson(d, s); ws.sendTXT(s);
}

void onWsEvent(WStype_t type, uint8_t* payload, size_t len) {
  if (type == WStype_CONNECTED) { sendRegister(); return; }
  if (type != WStype_TEXT) return;

  JsonDocument d; deserializeJson(d, payload, len);
  String t = d["type"] | "";

  if (t == "register_ack") {
    if (String(d["status"] | "") == "fail") {            // key sai -> xoa & provision lai
      nvs.remove("apiKey"); apiKey = ""; ws.disconnect();
    }
  } else if (t == "rotateKey") {
    String newKey = d["newApiKey"].as<String>();
    nvs.putString("apiKey", newKey); apiKey = newKey;    // GHI NVS TRUOC
    JsonDocument a;
    a["type"]="ackRotateKey"; a["deviceId"]=deviceId;
    a["status"]="success"; a["timestamp"]="";            // co the dat ISO-8601 UTC neu co RTC/NTP
    String s; serializeJson(a, s); ws.sendTXT(s);        // ack; neu mat -> lan sau register bang newKey van vao
  }
}

void setup() {
  // ... WiFi.begin(...) cho den khi WL_CONNECTED ...
  deviceId = hwDeviceId();
  while (!provisionIfNeeded()) delay(5000);              // Luong 1
  ws.beginSSL(HOST, 443, "/ws");                         // Luong 2 (wss)
  ws.onEvent(onWsEvent);
  ws.setReconnectInterval(5000);                         // reconnect co backoff
}

void loop() {
  ws.loop();
  if (millis() - lastBeat > 25000 && ws.isConnected()) { // Luong 2: heartbeat
    lastBeat = millis();
    JsonDocument d; d["type"]="heartbeat"; d["deviceId"]=deviceId;
    String s; serializeJson(d, s); ws.sendTXT(s);
  }
}
```

> Khung trên là tham khảo tối thiểu, đã phản ánh đúng protocol hiện có. Sản xuất nên: nạp CA thật thay `setInsecure()`, thêm backoff luỹ thừa, và kiểm tra WiFi trước mỗi lần gửi.
