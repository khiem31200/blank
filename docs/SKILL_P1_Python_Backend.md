# Skill P1 — Sửa API cho backend-service Python (3ddfa)

> Brief triển khai cho Claude Code, chạy trong **repo Python `3ddfa`** (KHÔNG phải repo view-service này).
> Bối cảnh: view-service (Spring Boot, orchestrator ESP32) đã code XONG Luồng 3 (enroll) + Luồng 4 (recognize)
> và đang gọi sang backend Python. **Chỉ MỘT thay đổi bắt buộc**: `/enroll` nhận thêm field `name`.
> Đây là BLOCKER — thiếu nó thì enroll từ web sẽ tạo identity **không có tên**.

---

## 0. Kiến trúc & vai trò (để hiểu ai gọi gì)

```
[ESP32 Cam] --wss--> [view-service :8081, Spring Boot]  = ORCHESTRATOR (đã xong, không đụng)
                              |  HTTP nội bộ Docker
                              v
                 [backend-service :8080, Python/FastAPI]  <-- SỬA Ở ĐÂY (1 chỗ duy nhất)
                 /enroll  /recognize  /identities  + PostgreSQL(pgvector) RDS
```

view-service đang gọi 3 endpoint với hợp đồng như sau (mục 2) — trong đó `/recognize` và `/identities`
**GIỮ NGUYÊN**, chỉ `/enroll` cần sửa.

## RÀNG BUỘC (đọc trước khi code)

1. **Chỉ sửa `api_server.py`, đúng 2 chỗ** như mục 1. KHÔNG đụng `db_utils.py` (hàm cần dùng —
   `update_person_name_db` — đã được import sẵn trong `api_server.py`).
2. KHÔNG đổi `/recognize`, `/identities`, schema DB, docker-compose, model AI.
3. Field `name` là **tùy chọn** (`None` mặc định) → client cũ gọi `/enroll` không kèm `name` vẫn chạy y như trước.
4. KHÔNG đổi format response của `/enroll` (`{"id": ..., "message": ...}`) — view-service parse đúng 2 field này.

---

# Task P1 — Thêm `name` cho `POST /enroll` (thay đổi DUY NHẤT)

**Chỗ 1 — model request** (thêm 1 field):

```python
class EnrollRequest(BaseModel):
    images: List[str] = Field(..., description="Danh sách ảnh base64.")
    name: str | None = Field(None, description="Tên người dùng (tùy chọn).")
```

**Chỗ 2 — trong hàm `enroll(...)`**, ngay sau khi có `new_id`:

```python
    new_id, message = enroll_person_db(mean_emb_np, None)
    if new_id == -1:
        raise HTTPException(status_code=500, detail=message)
    if request.name:                               # <-- THÊM 2 DÒNG NÀY
        update_person_name_db(new_id, request.name)
```

**Hành vi khi tên trùng:** `update_person_name_db` đã có sẵn xử lý trả lỗi **409** khi tên trùng
(cột `name` unique) — giữ nguyên, view-service sẽ bắt 409 và báo web. Không cần code thêm.

> ⚠️ Biết trước & chấp nhận: nếu 409 xảy ra thì identity **không tên** đã được tạo (enroll trước, gán tên sau).
> view-service đã giảm thiểu bằng cách **check trùng tên qua `GET /identities` TRƯỚC khi chụp ảnh**,
> nên 409 chỉ còn xảy ra khi có race (2 người tạo cùng tên cùng lúc). Không cần rollback — bỏ qua.

---

# 2. Hợp đồng API mà view-service đang dựa vào (đối chiếu, KHÔNG sửa gì thêm)

| Endpoint | view-service gửi | Mong đợi nhận | Timeout phía gọi |
|---|---|---|---|
| `POST /enroll` | `{"images": ["<base64>" x5], "name": "Nguyen Van A"}` | `200 {"id": <int >= 0>, "message": "..."}` · `409` nếu trùng tên · `!= 2xx` = lỗi | **20s** |
| `POST /recognize` | `{"images": ["<base64>"]}` (1 ảnh) | `200 {"identity": "<tên>" hoặc null, "confidence": <float>, "message": "..."}` | **10s** |
| `GET /identities` | (không body) | `200`, JSON chứa danh sách có field `name` (schema hiện tại giữ nguyên là được) | 10s |

Lưu ý vận hành đã biết (không cần sửa trong task này, chỉ để ý):
- Python chạy **1 worker uvicorn**, `/enroll` là `async def` chặn event loop → lúc enroll chạy,
  request khác phải chờ. view-service đã đặt timeout + hàng đợi ưu tiên phía nó rồi.
- `identity` trả `null` (hoặc thiếu / chuỗi rỗng / `"unknown"`) đều được view-service hiểu là **không khớp ai**.
- Python **tự ghi `recognition_logs`** khi khớp — giữ nguyên, view-service không ghi log thay.

---

# 3. Checklist nghiệm thu

- [ ] `POST /enroll` **không kèm** `name` → chạy y như cũ (backward compatible).
- [ ] `POST /enroll` kèm `name` mới → tạo identity, cột `name` trong DB đúng giá trị gửi lên, response vẫn `{"id","message"}`.
- [ ] `POST /enroll` kèm `name` **đã tồn tại** → trả `409`.
- [ ] `/recognize`, `/identities` không đổi hành vi (diff không chạm tới).
- [ ] Diff tổng cộng chỉ ~3 dòng trong `api_server.py`.

# 4. Test tay nhanh (sau khi deploy container backend)

```bash
# 1. Enroll kèm tên (thay <b64> bằng ảnh mặt base64 thật, đủ số ảnh service yêu cầu)
curl -s -X POST http://localhost:8080/enroll -H 'Content-Type: application/json' \
  -d '{"images":["<b64>","<b64>","<b64>","<b64>","<b64>"],"name":"Test P1"}'
# -> {"id":<n>,"message":"..."}

# 2. Xác nhận tên đã vào DB
curl -s http://localhost:8080/identities | grep "Test P1"

# 3. Gọi lại đúng tên đó -> phải ra 409
# 4. Xóa identity test khỏi DB sau khi xong
```

# 5. Việc cần con người xác nhận

1. Deploy lại container `backend-service` trên EC2 sau khi merge (view-service gọi qua Docker network `backend-service:8080`).
2. Báo lại cho phía view-service khi xong để chạy verify cloud end-to-end Luồng 3.
