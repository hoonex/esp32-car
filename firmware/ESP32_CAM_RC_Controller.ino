// ESP32-CAM RC Controller v3.0 — Android SPP + Wi-Fi unified firmware
// Keyestudio 2WD Camera Robot Car (AI Thinker ESP32-CAM + L298N)

#include <Arduino.h>
#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"
#include "img_converters.h"
#include "fb_gfx.h"
#include "soc/rtc_cntl_reg.h"
#include "driver/rtc_io.h"
#include <string.h>
#include "BluetoothSerial.h"
#include <Preferences.h>
#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <Update.h>
#include <esp_system.h>

// ===== 카메라 핀 정의 (AI Thinker) =====
#define PWDN_GPIO_NUM 32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 0
#define SIOD_GPIO_NUM 26
#define SIOC_GPIO_NUM 27
#define Y9_GPIO_NUM 35
#define Y8_GPIO_NUM 34
#define Y7_GPIO_NUM 39
#define Y6_GPIO_NUM 36
#define Y5_GPIO_NUM 21
#define Y4_GPIO_NUM 19
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM 23
#define PCLK_GPIO_NUM 22

// ===== 전역 변수 (한 번만 선언) =====
Preferences preferences;
BluetoothSerial SerialBT;

static const char* FW_VERSION = "3.1.0";
static const char* UPDATE_AP_SSID = "ESP32-CAR-UPDATE";
static const char* UPDATE_AP_PASSWORD = "esp32car";
String otaKey = "";
bool pendingRestart = false;
unsigned long restartAtMs = 0;

int MOTOR_R_PIN_1_v = 14;
int MOTOR_R_PIN_2_v = 15;
int MOTOR_L_PIN_1_v = 13;
int MOTOR_L_PIN_2_v = 12;
#define FLASH_LED_PIN 4
int FLASH_LED_PIN_v = 4;

String wifi_ssid = "";
String wifi_pass = "";

bool isBluetoothMode = false;
bool isWifiMode = false;
int  currentBtSpeed = 150;
int  currentBtTrim  = 0;

static httpd_handle_t stream_httpd = NULL;
static httpd_handle_t camera_httpd = NULL;
static int current_speed = 150;
static int current_trim  = 0;

String serialBuffer = "";
String btBuffer = "";

sensor_t *g_sensor = NULL;

bool pendingBtSwitch = false; // 블루투스 모드 전환 플래그

// ===== HTML 페이지 =====
static const char INDEX_HTML[] PROGMEM = R"=====(
<!doctype html><html><head><meta charset="utf-8"><title>ESP32-CAM RC</title>
<style>
body{font-family:sans-serif;margin:0;background:rgb(17,17,17);color:white}
header{padding:10px 16px;background:rgb(34,34,34)}
main{padding:12px}
button{margin:4px;padding:10px 14px;font-size:15px;border-radius:6px;border:none;background:#3a3a3a;color:white;cursor:pointer;transition:background 0.15s}
button:active{background:#666}
button.stop-btn{background:#a33}
img{max-width:100%}
.control-group{margin:10px 0}
input[type=range]{width:220px}
</style>
</head><body>
<header><h3>ESP32-CAM RC v2.2</h3></header>
<main>
<img id="stream" src=""><br/>
<div class="control-group">Speed: <input type="range" id="speed" min="50" max="255" value="150" oninput="updateSpeed(this.value)"><span id="speedVal">150</span></div>
<div class="control-group">Trim: <input type="range" id="trim" min="-50" max="50" value="0" oninput="updateTrim(this.value)"><span id="trimVal">0</span></div>
<div class="control-group">Light: <input type="range" id="light" min="0" max="255" value="0" oninput="updateLight(this.value)"><span id="lightVal">0</span></div>
<button onclick="go('forward')">Forward</button>
<button onclick="go('left')">Left</button>
<button onclick="go('right')">Right</button>
<button onclick="go('backward')">Backward</button>
<button class="stop-btn" onclick="go('stop')">Stop</button>
</main>
<script>
document.getElementById('stream').src = location.origin + ':81/stream';
let s=150, t=0;
function updateSpeed(v){s=v; document.getElementById('speedVal').innerText=v;}
function updateTrim(v){t=v; document.getElementById('trimVal').innerText=v;}
function updateLight(v){document.getElementById('lightVal').innerText=v; fetch('/action?light='+v).catch(()=>{});}
function go(c){fetch('/action?go='+c+'&speed='+s+'&trim='+t).catch(()=>{});}
</script>
</body></html>
)=====";

// ===== 모터 제어 =====
static inline void motors_stop(){
  ledcWrite(MOTOR_R_PIN_1_v, 0); ledcWrite(MOTOR_R_PIN_2_v, 0);
  ledcWrite(MOTOR_L_PIN_1_v, 0); ledcWrite(MOTOR_L_PIN_2_v, 0);
}
static inline void motors_forward(int speed, int trim){
  int sl = constrain(speed + trim, 0, 255);
  int sr = constrain(speed - trim, 0, 255);
  ledcWrite(MOTOR_R_PIN_1_v, 0);   ledcWrite(MOTOR_R_PIN_2_v, sr);
  ledcWrite(MOTOR_L_PIN_1_v, sl);  ledcWrite(MOTOR_L_PIN_2_v, 0);
}
static inline void motors_backward(int speed, int trim){
  int sl = constrain(speed + trim, 0, 255);
  int sr = constrain(speed - trim, 0, 255);
  ledcWrite(MOTOR_R_PIN_1_v, sr);  ledcWrite(MOTOR_R_PIN_2_v, 0);
  ledcWrite(MOTOR_L_PIN_1_v, 0);   ledcWrite(MOTOR_L_PIN_2_v, sl);
}
static inline void motors_left(int speed){
  speed = constrain(speed, 0, 255);
  ledcWrite(MOTOR_R_PIN_1_v, 0);   ledcWrite(MOTOR_R_PIN_2_v, speed);
  ledcWrite(MOTOR_L_PIN_1_v, 0);   ledcWrite(MOTOR_L_PIN_2_v, speed);
}
static inline void motors_right(int speed){
  speed = constrain(speed, 0, 255);
  ledcWrite(MOTOR_R_PIN_1_v, speed); ledcWrite(MOTOR_R_PIN_2_v, 0);
  ledcWrite(MOTOR_L_PIN_1_v, speed); ledcWrite(MOTOR_L_PIN_2_v, 0);
}

// ===== 모터 PWM 초기화 =====
#define MOTOR_PWM_FREQ  25000
#define LED_PWM_FREQ    5000

void setupMotorPWM(){
  ledcAttach(MOTOR_R_PIN_1_v, MOTOR_PWM_FREQ, 8);
  ledcAttach(MOTOR_R_PIN_2_v, MOTOR_PWM_FREQ, 8);
  ledcAttach(MOTOR_L_PIN_1_v, MOTOR_PWM_FREQ, 8);
  ledcAttach(MOTOR_L_PIN_2_v, MOTOR_PWM_FREQ, 8);
  ledcAttach(FLASH_LED_PIN_v, LED_PWM_FREQ, 8);
  ledcWrite(FLASH_LED_PIN_v, 0);
  motors_stop();
}

// ===== OTA key / OTA =====
String loadOrCreateOtaKey(){
  preferences.begin("ota", false);
  String key = preferences.getString("key", "");
  if(key.length() < 8){
    uint32_t r = esp_random();
    char buf[17];
    snprintf(buf, sizeof(buf), "%08lX%08lX", (unsigned long)r, (unsigned long)(ESP.getEfuseMac() & 0xFFFFFFFFULL));
    key = String(buf);
    preferences.putString("key", key);
  }
  preferences.end();
  return key;
}

bool otaAuthorized(httpd_req_t *req){
  size_t len = httpd_req_get_hdr_value_len(req, "X-ESP32-OTA-Key");
  if(len == 0 || len > 64) return false;
  char value[65];
  if(httpd_req_get_hdr_value_str(req, "X-ESP32-OTA-Key", value, sizeof(value)) != ESP_OK) return false;
  return otaKey == String(value);
}

void setupOTA(){
  ArduinoOTA.setHostname("esp32cam-rc");
  ArduinoOTA.setPassword(otaKey.c_str());
  ArduinoOTA.onStart([](){ motors_stop(); Serial.println("[OTA] 시작"); });
  ArduinoOTA.onEnd([](){ Serial.println("\n[OTA] 완료, 리부팅"); });
  ArduinoOTA.onProgress([](unsigned int p, unsigned int t){
    Serial.printf("[OTA] %u%%\r", (p*100)/t);
  });
  ArduinoOTA.onError([](ota_error_t err){ Serial.printf("[OTA] 에러 %u\n", err); });
  ArduinoOTA.begin();
}

// ===== Wi-Fi 크리덴셜 =====
void loadWifiCredentials(){
  preferences.begin("wifi_creds", true);
  wifi_ssid = preferences.getString("ssid", "");
  wifi_pass = preferences.getString("pass", "");
  preferences.end();
}
void saveWifiCredentials(const String &ssid, const String &pass){
  preferences.begin("wifi_creds", false);
  preferences.putString("ssid", ssid);
  preferences.putString("pass", pass);
  preferences.end();
}

void savePreferredMode(const char* mode){
  preferences.begin("rc_mode", false);
  preferences.putString("mode", mode);
  preferences.end();
}

bool connectWiFi(){
  if(wifi_ssid.length() == 0) return false;
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(UPDATE_AP_SSID, UPDATE_AP_PASSWORD);
  WiFi.begin(wifi_ssid.c_str(), wifi_pass.c_str());
  int attempts = 0;
  while(WiFi.status() != WL_CONNECTED && attempts < 20){
    delay(500);
    attempts++;
  }
  return (WiFi.status() == WL_CONNECTED);
}

// ===== 모드 전환 (3초 오버랩) =====
void switchToWifiMode(){
  Serial.println("[전환] BT -> Wi-Fi (오버랩 시작)");
  loadWifiCredentials();
  if(wifi_ssid.length() == 0){
    Serial.println("[전환 실패] 저장된 Wi-Fi 없음 - BT 유지, 프로비저닝 필요");
    SerialBT.println("ERR:NO_WIFI_CREDENTIALS");
    return;
  }
  if(!connectWiFi()){
    Serial.println("[전환 실패] Wi-Fi 연결 실패 - BT 유지");
    SerialBT.println("ERR:WIFI_CONNECT_FAILED");
    return;
  }
  startCamera();
  startCameraServer();
  setupOTA();
  savePreferredMode("WIFI");
  Serial.println("[오버랩] 3초간 BT+Wi-Fi 동시 운영");
  SerialBT.println("OK:WIFI_CONNECTED:" + WiFi.localIP().toString());
  delay(3000);
  SerialBT.end();
  isBluetoothMode = false;
  isWifiMode = true;
  Serial.print("[전환 완료] Wi-Fi IP: ");
  Serial.println(WiFi.localIP());
}

void switchToBluetoothMode(){
  Serial.println("[전환] Wi-Fi -> BT (오버랩 시작)");
  SerialBT.begin("ESP32_CAM_RC");
  isBluetoothMode = true;
  savePreferredMode("BT");
  Serial.println("[오버랩] 3초간 Wi-Fi+BT 동시 운영");
  delay(3000);
  stopCameraServer();
  ArduinoOTA.end();
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  isWifiMode = false;
  Serial.println("[전환 완료] BT만 활성");
}

// ===== BT 명령 처리 =====
void processBluetoothCommand(String cmd){
  cmd.trim();
  if(cmd.length() == 0) return;

  if(cmd == "STATUS"){
    loadWifiCredentials();
    if(isWifiMode){
      char json[256];
      snprintf(json, sizeof(json), "{\"mode\":\"WIFI\",\"ip\":\"%s\",\"ssid\":\"%s\",\"rssi\":%ld,\"fw\":\"%s\",\"ota\":true,\"ota_key\":\"%s\"}", WiFi.localIP().toString().c_str(), WiFi.SSID().c_str(), WiFi.RSSI(), FW_VERSION, otaKey.c_str());
      SerialBT.println(json);
    } else {
      if(wifi_ssid.length() > 0){
        SerialBT.println("{\"mode\":\"BT\",\"has_credentials\":true,\"ssid\":\"" + wifi_ssid + "\",\"fw\":\"" + String(FW_VERSION) + "\",\"ota\":true,\"ota_key\":\"" + otaKey + "\"}");
      } else {
        SerialBT.println("{\"mode\":\"BT\",\"has_credentials\":false,\"fw\":\"" + String(FW_VERSION) + "\",\"ota\":true,\"ota_key\":\"" + otaKey + "\"}");
      }
    }
  }
  else if(cmd.startsWith("W:")){
    String rest = cmd.substring(2);
    int comma = rest.indexOf(',');
    if(comma < 0){ SerialBT.println("ERR:FORMAT"); return; }
    String newSsid = rest.substring(0, comma);
    String newPass = rest.substring(comma + 1);
    if(newSsid.length() == 0){ SerialBT.println("ERR:EMPTY_SSID"); return; }
    saveWifiCredentials(newSsid, newPass);
    SerialBT.println("OK:WIFI_SAVED:" + newSsid);
    Serial.println("[프로비저닝] Wi-Fi 저장: " + newSsid);
  }
  else if(cmd == "X"){ switchToWifiMode(); }
  else if(cmd == "U"){
    motors_stop();
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(UPDATE_AP_SSID, UPDATE_AP_PASSWORD);
    if(!camera_httpd){
      startCamera();
      startCameraServer();
      setupOTA();
    }
    isWifiMode = true;
    SerialBT.println("OK:OTA_AP:192.168.4.1");
  }
  else if(cmd.startsWith("V")){
    int v = cmd.substring(1).toInt();
    if(v >= 50 && v <= 255) currentBtSpeed = v;
  }
  else if(cmd.startsWith("T")){
    int t = cmd.substring(1).toInt();
    if(t >= -50 && t <= 50) currentBtTrim = t;
  }
  else if(cmd.startsWith("H")){
    int h = cmd.substring(1).toInt();
    if(h >= 0 && h <= 255) ledcWrite(FLASH_LED_PIN_v, h);
  }
  else if(cmd == "F")      motors_forward(currentBtSpeed, currentBtTrim);
  else if(cmd == "B")      motors_backward(currentBtSpeed, currentBtTrim);
  else if(cmd == "L")      motors_left(currentBtSpeed);
  else if(cmd == "R")      motors_right(currentBtSpeed);
  else if(cmd == "S")      motors_stop();
  else { SerialBT.println("ERR:UNKNOWN_CMD:" + cmd); }
}

// ===== 시리얼 (USB) 명령 =====
void handleSerialCommand(String line){
  line.trim();
  if(line.length() == 0) return;
  if(line.startsWith("WIFI:")){
    int c = line.indexOf(',');
    if(c < 0){ Serial.println("[에러] WIFI:SSID,PASSWORD"); return; }
    saveWifiCredentials(line.substring(5, c), line.substring(c+1));
    Serial.println("[OK] Wi-Fi 저장, 재부팅");
    delay(500); ESP.restart();
  }
  else if(line == "HELP" || line == "?"){
    Serial.println("명령: WIFI:SSID,PASS | HELP");
  }
  else Serial.println("[알 수 없음] " + line);
}

// ===== 스트리밍 핸들러 =====
static const char* _STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=frame";
static const char* _STREAM_BOUNDARY = "\r\n--frame\r\n";
static const char* _STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static esp_err_t stream_handler(httpd_req_t *req){
  camera_fb_t *fb = NULL;
  esp_err_t res = httpd_resp_set_type(req, _STREAM_CONTENT_TYPE);
  if(res != ESP_OK) return res;
  const char* boundary = _STREAM_BOUNDARY;
  const char* part     = _STREAM_PART;

  while(true){
    fb = esp_camera_fb_get();
    if(!fb){ res = ESP_FAIL; break; }
    uint8_t *jpg_buf = NULL; size_t jpg_len = 0;
    if(fb->format != PIXFORMAT_JPEG){
      bool ok = frame2jpg(fb, 80, &jpg_buf, &jpg_len);
      esp_camera_fb_return(fb); fb = NULL;
      if(!ok){ res = ESP_FAIL; break; }
    } else { jpg_buf = fb->buf; jpg_len = fb->len; }

    char hdr[64];
    size_t hlen = snprintf(hdr, sizeof(hdr), part, (unsigned)jpg_len);
    if(httpd_resp_send_chunk(req, boundary, strlen(boundary)) != ESP_OK ||
       httpd_resp_send_chunk(req, hdr, hlen) != ESP_OK ||
       httpd_resp_send_chunk(req, (const char*)jpg_buf, jpg_len) != ESP_OK){
      res = ESP_FAIL;
    }
    if(fb){ esp_camera_fb_return(fb); fb = NULL; }
    else if(jpg_buf){ free(jpg_buf); jpg_buf = NULL; }
    if(res != ESP_OK) break;
  }
  return res;
}

// ===== 고해상도 캡처 핸들러 (GET /capture) =====
static esp_err_t capture_handler(httpd_req_t *req){
  if(!g_sensor) return httpd_resp_send_500(req);

  framesize_t old_size = g_sensor->status.framesize;
  int old_quality = g_sensor->status.quality;

  g_sensor->set_framesize(g_sensor, FRAMESIZE_UXGA);
  g_sensor->set_quality(g_sensor, 8);
  delay(150);

  camera_fb_t *fb = esp_camera_fb_get();
  if(!fb){
    g_sensor->set_framesize(g_sensor, old_size);
    g_sensor->set_quality(g_sensor, old_quality);
    return httpd_resp_send_500(req);
  }

  g_sensor->set_framesize(g_sensor, old_size);
  g_sensor->set_quality(g_sensor, old_quality);

  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Content-Disposition", "inline; filename=capture.jpg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store, no-cache, must-revalidate");
  esp_err_t res = httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

// ===== OTA HTTP handlers =====
static esp_err_t ota_info_handler(httpd_req_t *req){
  char json[256];
  String ip = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
  snprintf(json, sizeof(json), "{\"fw\":\"%s\",\"ota\":true,\"mode\":\"%s\",\"ip\":\"%s\",\"update_ap\":\"%s\"}",
           FW_VERSION, isWifiMode ? "WIFI" : "BT", ip.c_str(), UPDATE_AP_SSID);
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, json, strlen(json));
}

static esp_err_t ota_upload_handler(httpd_req_t *req){
  if(!otaAuthorized(req)){
    httpd_resp_set_status(req, "401 Unauthorized");
    httpd_resp_set_type(req, "application/json");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"unauthorized\"}");
  }
  if(req->content_len <= 0){
    httpd_resp_set_status(req, "400 Bad Request");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"empty_firmware\"}");
  }

  motors_stop();
  if(!Update.begin(req->content_len, U_FLASH)){
    httpd_resp_set_status(req, "500 Internal Server Error");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"update_begin_failed\"}");
  }

  uint8_t buf[4096];
  int remaining = req->content_len;
  while(remaining > 0){
    int received = httpd_req_recv(req, (char*)buf, min(remaining, (int)sizeof(buf)));
    if(received == HTTPD_SOCK_ERR_TIMEOUT) continue;
    if(received <= 0){
      Update.abort();
      httpd_resp_set_status(req, "500 Internal Server Error");
      return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"receive_failed\"}");
    }
    if(Update.write(buf, received) != (size_t)received){
      Update.abort();
      httpd_resp_set_status(req, "500 Internal Server Error");
      return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"flash_write_failed\"}");
    }
    remaining -= received;
  }

  if(!Update.end(true)){
    httpd_resp_set_status(req, "500 Internal Server Error");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"verification_failed\"}");
  }

  httpd_resp_set_type(req, "application/json");
  httpd_resp_sendstr(req, "{\"ok\":true,\"rebooting\":true}");
  pendingRestart = true;
  restartAtMs = millis() + 1200;
  return ESP_OK;
}

// ===== /action 핸들러 =====
static esp_err_t action_handler(httpd_req_t *req){
  char *buf = NULL;
  size_t buf_len = httpd_req_get_url_query_len(req) + 1;
  if(buf_len > 1){
    buf = (char*)malloc(buf_len);
    if(httpd_req_get_url_query_str(req, buf, buf_len) == ESP_OK){
      char param[32], speed_param[16], trim_param[16], light_param[16];

      if(httpd_query_key_value(buf, "light", light_param, sizeof(light_param)) == ESP_OK){
        ledcWrite(FLASH_LED_PIN_v, constrain(atoi(light_param), 0, 255));
      }
      if(httpd_query_key_value(buf, "speed", speed_param, sizeof(speed_param)) == ESP_OK){
        current_speed = constrain(atoi(speed_param), 0, 255);
      }
      if(httpd_query_key_value(buf, "trim", trim_param, sizeof(trim_param)) == ESP_OK){
        current_trim = constrain(atoi(trim_param), -100, 100);
      }
      if(httpd_query_key_value(buf, "stream_quality", speed_param, sizeof(speed_param)) == ESP_OK){
        if(g_sensor) g_sensor->set_quality(g_sensor, constrain(atoi(speed_param), 4, 20));
      }
      if(httpd_query_key_value(buf, "stream_size", speed_param, sizeof(speed_param)) == ESP_OK){
        if(g_sensor) g_sensor->set_framesize(g_sensor, (framesize_t)atoi(speed_param));
      }
      if(httpd_query_key_value(buf, "go", param, sizeof(param)) == ESP_OK){
        if(!strcmp(param, "STATUS")){
          char json[256];
          long rssi = WiFi.RSSI();
          snprintf(json, sizeof(json), "{\"mode\":\"WIFI\",\"ip\":\"%s\",\"ssid\":\"%s\",\"rssi\":%ld,\"fw\":\"%s\",\"ota\":true,\"update_ap\":\"%s\"}", WiFi.localIP().toString().c_str(), WiFi.SSID().c_str(), rssi, FW_VERSION, UPDATE_AP_SSID);
          httpd_resp_set_type(req, "application/json");
          return httpd_resp_send(req, json, strlen(json));
        }
        else if(!strcmp(param, "SCAN")){
          int n = WiFi.scanNetworks();
          String json = "[";
          for (int i = 0; i < n; ++i) {
            if(i > 0) json += ",";
            json += "{\"ssid\":\"" + WiFi.SSID(i) + "\",\"rssi\":" + String(WiFi.RSSI(i)) + "}";
          }
          json += "]";
          httpd_resp_set_type(req, "application/json");
          return httpd_resp_send(req, json.c_str(), json.length());
        }
        else if(!strcmp(param, "MODE:BT")) {
          pendingBtSwitch = true;
          httpd_resp_set_type(req, "text/plain");
          return httpd_resp_send(req, "OK:SWITCHING_TO_BT", strlen("OK:SWITCHING_TO_BT"));
        }
        else if(!strcmp(param, "forward"))      motors_forward(current_speed, current_trim);
        else if(!strcmp(param, "backward")) motors_backward(current_speed, current_trim);
        else if(!strcmp(param, "left"))     motors_left(current_speed);
        else if(!strcmp(param, "right"))    motors_right(current_speed);
        else if(!strcmp(param, "bt_mode"))  pendingBtSwitch = true; // legacy support
        else                                motors_stop();
      }
    }
    free(buf);
  }
  httpd_resp_set_type(req, "text/plain");
  return httpd_resp_send(req, "OK", 2);
}

// ===== 인덱스 핸들러 =====
static esp_err_t index_handler(httpd_req_t *req){
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, (const char*)INDEX_HTML, strlen(INDEX_HTML));
}

// ===== 서버 시작/종료 =====
void startCameraServer(){
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80; config.ctrl_port = 32767;
  if(httpd_start(&camera_httpd, &config) == ESP_OK){
    httpd_uri_t index_uri  = { .uri="/",       .method=HTTP_GET, .handler=index_handler,  .user_ctx=NULL };
    httpd_uri_t action_uri = { .uri="/action", .method=HTTP_GET, .handler=action_handler, .user_ctx=NULL };
    httpd_uri_t capture_uri = { .uri="/capture", .method=HTTP_GET, .handler=capture_handler, .user_ctx=NULL };
    httpd_uri_t ota_info_uri = { .uri="/api/info", .method=HTTP_GET, .handler=ota_info_handler, .user_ctx=NULL };
    httpd_uri_t ota_upload_uri = { .uri="/api/ota", .method=HTTP_POST, .handler=ota_upload_handler, .user_ctx=NULL };
    httpd_register_uri_handler(camera_httpd, &index_uri);
    httpd_register_uri_handler(camera_httpd, &action_uri);
    httpd_register_uri_handler(camera_httpd, &capture_uri);
    httpd_register_uri_handler(camera_httpd, &ota_info_uri);
    httpd_register_uri_handler(camera_httpd, &ota_upload_uri);
  }
  httpd_config_t conf2 = HTTPD_DEFAULT_CONFIG();
  conf2.server_port = 81; conf2.ctrl_port = 32766;
  if(httpd_start(&stream_httpd, &conf2) == ESP_OK){
    httpd_uri_t stream_uri = { .uri="/stream", .method=HTTP_GET, .handler=stream_handler, .user_ctx=NULL };
    httpd_register_uri_handler(stream_httpd, &stream_uri);
  }
}
void stopCameraServer(){
  if(camera_httpd){ httpd_stop(camera_httpd); camera_httpd = NULL; }
  if(stream_httpd){ httpd_stop(stream_httpd); stream_httpd = NULL; }
}

// ===== 카메라 초기화 =====
void startCamera(){
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer    = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;   config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM; config.pin_href  = HREF_GPIO_NUM;
  config.pin_sscb_sda = SIOD_GPIO_NUM; config.pin_sscb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;

  if(psramFound()){
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 8;
    config.fb_count = 2;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_LATEST;
  } else {
    config.frame_size = FRAMESIZE_QQVGA;
    config.jpeg_quality = 10;
    config.fb_count = 1;
  }
  esp_err_t err = esp_camera_init(&config);
  if(err != ESP_OK){
    Serial.printf("Camera init failed: 0x%x\n", err);
    ESP.restart();
  }
  g_sensor = esp_camera_sensor_get();
  if(g_sensor) g_sensor->set_vflip(g_sensor, 1);
}

// ===== setup =====
void setup(){
  Serial.begin(115200);
  delay(200);
  setupMotorPWM();
  otaKey = loadOrCreateOtaKey();
  Serial.printf("\n===== ESP32-CAM RC v%s =====\n", FW_VERSION);

  preferences.begin("rc_mode", true);
  String mode = preferences.getString("mode", "BT");
  preferences.end();

  if(mode == "WIFI"){
    Serial.println("[부팅] 저장된 Wi-Fi 모드로 시작");
    loadWifiCredentials();
    if(wifi_ssid.length() > 0 && connectWiFi()){
      Serial.print("  IP: "); Serial.println(WiFi.localIP());
      startCamera();
      startCameraServer();
      setupOTA();
      isWifiMode = true;
      Serial.println("  OTA: esp32cam-rc.local");
    } else {
      Serial.println("  [실패] Wi-Fi 연결 실패 -> BT 폴백");
      WiFi.disconnect(true);
      WiFi.mode(WIFI_OFF);
      SerialBT.begin("ESP32_CAM_RC");
      isBluetoothMode = true;
      savePreferredMode("BT");
    }
  } else {
    Serial.println("[부팅] 저장된 BT 모드로 시작");
    SerialBT.begin("ESP32_CAM_RC");
    isBluetoothMode = true;
    savePreferredMode("BT");
  }
  Serial.println("============================\n");
}

// ===== loop =====
void loop(){
  if(pendingRestart && (long)(millis() - restartAtMs) >= 0){
    motors_stop();
    delay(100);
    ESP.restart();
  }

  if (pendingBtSwitch) {
    pendingBtSwitch = false;
    switchToBluetoothMode();
  }

  if(isWifiMode) ArduinoOTA.handle();

  if(isBluetoothMode && SerialBT.available()){
    char c = SerialBT.read();
    if(c == '\n'){
      processBluetoothCommand(btBuffer);
      btBuffer = "";
    } else if(c != '\r'){
      btBuffer += c;
      if(btBuffer.length() > 128) btBuffer = "";
    }
  }

  while(Serial.available()){
    char c = Serial.read();
    if(c == '\n'){
      handleSerialCommand(serialBuffer);
      serialBuffer = "";
    } else if(c != '\r'){
      serialBuffer += c;
      if(serialBuffer.length() > 64) serialBuffer = "";
    }
  }
  delay(10);
}
