# ============================================================
# ESP32-CAM SIMULATOR - gia lap thiet bi that de test UI/luong
#
# Chay LOCAL (server IntelliJ http://localhost:8082):
#   powershell -File scripts\esp32-sim.ps1
# Chay CLOUD (qua nginx TLS):
#   powershell -File scripts\esp32-sim.ps1 -BaseUrl https://iot-project.io.vn -Secret <PROVISIONING_SECRET tren EC2>
# Phim:   [R] gia lap BAM NUT nhan dien (gui recognize_image)
#         [Q] thoat
# LUU Y: local la http:// (khong TLS) — dung https://localhost se loi handshake.
#
# Hanh vi (bam sat firmware that):
#  - Luong 1: NVS trong -> POST /device/register lay apiKey
#  - Luong 2: mo WS, gui register, heartbeat 25s, tu reconnect
#  - Luong 3: nhan start_enroll -> gui du $EnrollImages anh
#             (dat -EnrollImages 2 de demo timeout)
#  - Luong 4: phim R -> gui recognize_image, in recognize_result
#  - Luong 5: nhan rotateKey -> luu key moi -> ackRotateKey
# ============================================================
param(
    [string]$BaseUrl      = 'http://localhost:8082',
    [string]$DeviceId     = 'esp32-sim-01',
    [string]$Secret       = 'dev-secret-local',
    [string]$ImagePath    = 'D:\pro-sto\Mesh3d\examples\inputs\emma.jpg',
    [int]$EnrollImages    = 5,
    [int]$ImageDelayMs    = 400
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing

# TLS 1.2 cho PS 5.1 khi goi https/wss (cloud); vo hai voi http local
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

# Chan nham lan: localhost khong co TLS
if ($BaseUrl -match '^https://localhost') {
    Write-Host "Server local khong co TLS -> dung http://localhost:8082 (hoac -BaseUrl https://iot-project.io.vn cho cloud)" -ForegroundColor Red
    exit 1
}

$wsUri = ($BaseUrl -replace '^http', 'ws') + '/ws'   # http->ws, https->wss

function Log($color, $msg) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg) -ForegroundColor $color }

# ---- "Chup anh": load + resize 480px -> base64 (nhu anh VGA tu cam that) ----
$srcImg = [System.Drawing.Image]::FromFile($ImagePath)
$w = 480; $h = [int]($srcImg.Height * $w / $srcImg.Width)
$bmp = New-Object System.Drawing.Bitmap($srcImg, $w, $h)
$ms = New-Object System.IO.MemoryStream
$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]85)
$bmp.Save($ms, $codec, $ep)
$imgB64 = [Convert]::ToBase64String($ms.ToArray())
$srcImg.Dispose(); $bmp.Dispose(); $ms.Dispose()
Log Cyan "Camera gia lap: $ImagePath -> ${w}x${h} (base64 $($imgB64.Length) chars)"

# ---- Luong 1: xin apiKey (moi lan chay xin lai = re-register, server cho phep) ----
$http = New-Object System.Net.Http.HttpClient
$body = (@{ deviceId = $DeviceId; secret = $Secret } | ConvertTo-Json -Compress)
$content = New-Object System.Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')
$res = $http.PostAsync("$BaseUrl/device/register", $content).GetAwaiter().GetResult()
$resBody = $res.Content.ReadAsStringAsync().GetAwaiter().GetResult()
if ([int]$res.StatusCode -ne 200) { Log Red "Register REST that bai: HTTP $([int]$res.StatusCode) $resBody"; exit 1 }
$script:apiKey = ($resBody | ConvertFrom-Json).apiKey
Log Green "Luong 1 OK: da duoc cap apiKey ($($script:apiKey.Substring(0,8))...)"

# ---- WS helpers ----
function WsSend($ws, $obj) {
    $json = $obj | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(,$bytes)
    $null = $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function Handle-Message($ws, $raw) {
    $m = $raw | ConvertFrom-Json
    switch ($m.type) {
        'register_ack' {
            if ($m.status -eq 'success') { Log Green "Luong 2 OK: register_ack success -> thiet bi ONLINE. Mo dashboard va bam 'Dang ky khuon mat'!" }
            else { Log Red "register_ack FAIL -> key sai? Thoat."; $script:quit = $true }
        }
        'start_enroll' {
            Log Yellow "Luong 3: nhan start_enroll (session=$($m.sessionId), can $($m.count) anh) -> bat dau 'chup'..."
            $script:stopEnroll = $false
            for ($i = 1; $i -le $EnrollImages; $i++) {
                if ($script:stopEnroll) { Log DarkYellow "  dung chup theo lenh stop_enroll"; break }
                Start-Sleep -Milliseconds $ImageDelayMs
                WsSend $ws @{ type = 'enroll_image'; sessionId = $m.sessionId; deviceId = $DeviceId; image = $imgB64 }
                Log Gray "  da gui anh $i/$($m.count)"
            }
        }
        'stop_enroll' {
            $script:stopEnroll = $true
            Log DarkYellow "Nhan stop_enroll (session=$($m.sessionId)) -> phien da bi huy/het gio"
        }
        'recognize_result' {
            if ($m.status -eq 'ok') { Log Green "Luong 4: NHAN DIEN KHOP -> $($m.identity) (confidence=$([math]::Round($m.confidence,4)))" }
            elseif ($m.status -eq 'unknown') { Log Yellow "Luong 4: khong khop ai (confidence=$($m.confidence))" }
            else { Log Red "Luong 4: loi -> $($m.reason)" }
        }
        'busy' { Log DarkYellow "Server bao busy ($($m.reason)) -> thu lai sau" }
        'rotateKey' {
            $script:apiKey = $m.newApiKey                       # "ghi NVS truoc"
            WsSend $ws @{ type = 'ackRotateKey'; deviceId = $DeviceId; status = 'success'; timestamp = (Get-Date -Format o) }
            Log Cyan "Luong 5: nhan rotateKey -> da doi key + gui ackRotateKey"
        }
        'error' { Log Red "Server bao loi: $($m.reason)" }
        default { Log Gray "Nhan message: $raw" }
    }
}

# ---- Vong doi chinh: connect -> register -> lang nghe + heartbeat + phim tat ----
$script:quit = $false
Log Cyan "Dieu khien: [R] = bam nut nhan dien | [Q] = thoat"
while (-not $script:quit) {
    $ws = $null
    try {
        $ws = New-Object System.Net.WebSockets.ClientWebSocket
        $null = $ws.ConnectAsync([Uri]$wsUri, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        WsSend $ws @{ type = 'register'; deviceId = $DeviceId; apiKey = $script:apiKey }

        # bo thu nhan khong chan: giu 1 ReceiveAsync dang cho + gop frame toi EndOfMessage
        $buf = New-Object byte[] 262144
        $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(,$buf)
        $sb = New-Object Text.StringBuilder
        $recvTask = $ws.ReceiveAsync($seg, [Threading.CancellationToken]::None)
        $lastBeat = Get-Date

        while ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open -and -not $script:quit) {
            # 1) co du lieu WS?
            if ($recvTask.IsCompleted) {
                $r = $recvTask.GetAwaiter().GetResult()
                if ($r.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { throw "server dong ket noi" }
                [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $r.Count))
                if ($r.EndOfMessage) {
                    $raw = $sb.ToString(); [void]$sb.Clear()
                    Handle-Message $ws $raw
                }
                $recvTask = $ws.ReceiveAsync($seg, [Threading.CancellationToken]::None)
            }
            # 2) phim tat (bo qua neu console khong ho tro)
            try {
                if ([Console]::KeyAvailable) {
                    $k = [Console]::ReadKey($true).Key
                    if ($k -eq 'R') {
                        Log Yellow "BAM NUT -> chup 1 anh + gui recognize_image..."
                        WsSend $ws @{ type = 'recognize_image'; deviceId = $DeviceId; image = $imgB64 }
                    } elseif ($k -eq 'Q') { $script:quit = $true }
                }
            } catch {}
            # 3) heartbeat 25s
            if (((Get-Date) - $lastBeat).TotalSeconds -ge 25) {
                WsSend $ws @{ type = 'heartbeat'; deviceId = $DeviceId }
                $lastBeat = Get-Date
            }
            Start-Sleep -Milliseconds 50
        }
    } catch {
        Log Red "Mat ket noi: $($_.Exception.Message)"
    } finally {
        if ($ws) { try { $ws.Dispose() } catch {} }
    }
    if (-not $script:quit) { Log DarkYellow "Reconnect sau 3s (nhu firmware that)..."; Start-Sleep 3 }
}
Log Cyan "Da thoat ESP32 simulator."
