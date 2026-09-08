// ESP32-CAM RC Controller v4.0.0
// Rebuilt from the user's original working v3.0 sketch.
// Architecture: Bluetooth = drive/safety, Wi-Fi = camera/status/HTTP OTA.
// Target: AI Thinker ESP32-CAM + OV2640 + Keyestudio/2WD chassis + L298N.

#include <Arduino.h>
#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"
#include "BluetoothSerial.h"
#include <Preferences.h>
#include <Update.h>
#include <esp_system.h>

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

static const int MOTOR_R_PIN_1 = 14;
static const int MOTOR_R_PIN_2 = 15;
static const int MOTOR_L_PIN_1 = 13;
static const int MOTOR_L_PIN_2 = 12;
static const int FLASH_LED_PIN = 4;
static const int MOTOR_PWM_FREQ = 25000;
static const int LED_PWM_FREQ = 5000;
static const int PWM_BITS = 8;

// Camera XCLK explicitly owns LEDC channel 0. Arduino-ESP32 3.x auto-assigns
// ledcAttach() from the first free channel, so reserve the opposite LEDC group
// for motors/flash instead of allowing a camera/PWM collision.
static const int MOTOR_R_CH_1 = 8;
static const int MOTOR_R_CH_2 = 9;
static const int MOTOR_L_CH_1 = 10;
static const int MOTOR_L_CH_2 = 11;
static const int FLASH_LED_CH = 12;

static const char* FW_VERSION = "4.0.0";
static const int PROTOCOL_VERSION = 2;
static const char* HARDWARE_PROFILE = "AI_THINKER_ESP32_CAM_2WD_L298N";
static const uint32_t DRIVE_DEADMAN_MS = 650;

Preferences preferences;
BluetoothSerial SerialBT;
String wifiSsid;
String wifiPass;
String otaKey;
String btBuffer;
String serialBuffer;

bool wifiConnected = false;
bool cameraReady = false;
bool httpReady = false;
bool streamReady = false;
bool pendingRestart = false;
uint32_t restartAtMs = 0;
uint32_t lastDriveCommandMs = 0;
bool driveActive = false;
int currentSpeed = 255;
int currentTrim = 0;
int currentLeft = 0;
int currentRight = 0;
int streamFps = 12;
bool motorSwap = false;
bool invertLeft = false;
bool invertRight = false;
sensor_t* cameraSensor = nullptr;
httpd_handle_t controlHttpd = nullptr;
httpd_handle_t streamHttpd = nullptr;

void loadWifiCredentials() {
  preferences.begin("wifi_creds", true);
  wifiSsid = preferences.getString("ssid", "");
  wifiPass = preferences.getString("pass", "");
  preferences.end();
}

void saveWifiCredentials(const String& ssid, const String& pass) {
  preferences.begin("wifi_creds", false);
  preferences.putString("ssid", ssid);
  preferences.putString("pass", pass);
  preferences.end();
  wifiSsid = ssid;
  wifiPass = pass;
}

void loadMotorConfig() {
  preferences.begin("motor_cfg", true);
  motorSwap = preferences.getBool("swap", false);
  invertLeft = preferences.getBool("inv_l", false);
  invertRight = preferences.getBool("inv_r", false);
  preferences.end();
}

void saveMotorConfig() {
  preferences.begin("motor_cfg", false);
  preferences.putBool("swap", motorSwap);
  preferences.putBool("inv_l", invertLeft);
  preferences.putBool("inv_r", invertRight);
  preferences.end();
}

String loadOrCreateOtaKey() {
  preferences.begin("ota", false);
  String key = preferences.getString("key", "");
  if (key.length() < 8) {
    char buffer[17];
    snprintf(buffer, sizeof(buffer), "%08lX%08lX", (unsigned long)esp_random(), (unsigned long)(ESP.getEfuseMac() & 0xFFFFFFFFULL));
    key = String(buffer);
    preferences.putString("key", key);
  }
  preferences.end();
  return key;
}

void setupMotorPwm() {
  bool ok = true;
  ok &= ledcAttachChannel(MOTOR_R_PIN_1, MOTOR_PWM_FREQ, PWM_BITS, MOTOR_R_CH_1);
  ok &= ledcAttachChannel(MOTOR_R_PIN_2, MOTOR_PWM_FREQ, PWM_BITS, MOTOR_R_CH_2);
  ok &= ledcAttachChannel(MOTOR_L_PIN_1, MOTOR_PWM_FREQ, PWM_BITS, MOTOR_L_CH_1);
  ok &= ledcAttachChannel(MOTOR_L_PIN_2, MOTOR_PWM_FREQ, PWM_BITS, MOTOR_L_CH_2);
  ok &= ledcAttachChannel(FLASH_LED_PIN, LED_PWM_FREQ, PWM_BITS, FLASH_LED_CH);
  ledcWrite(FLASH_LED_PIN, 0);
  Serial.printf("[PWM] motor/flash channels isolated from camera: %s\n", ok ? "OK" : "FAILED");
}

static inline int applyInvert(int value, bool invert) { return invert ? -value : value; }

void writeMotorPair(int pin1, int pin2, int value) {
  value = constrain(value, -255, 255);
  if (value > 0) {
    ledcWrite(pin1, 0);
    ledcWrite(pin2, value);
  } else if (value < 0) {
    ledcWrite(pin1, -value);
    ledcWrite(pin2, 0);
  } else {
    ledcWrite(pin1, 0);
    ledcWrite(pin2, 0);
  }
}

void motorsSet(int logicalLeft, int logicalRight) {
  logicalLeft = constrain(logicalLeft, -255, 255);
  logicalRight = constrain(logicalRight, -255, 255);
  if (motorSwap) {
    int tmp = logicalLeft;
    logicalLeft = logicalRight;
    logicalRight = tmp;
  }
  logicalLeft = applyInvert(logicalLeft, invertLeft);
  logicalRight = applyInvert(logicalRight, invertRight);
  // Same physical polarity as the original working v3.0 sketch.
  writeMotorPair(MOTOR_L_PIN_2, MOTOR_L_PIN_1, logicalLeft);
  writeMotorPair(MOTOR_R_PIN_1, MOTOR_R_PIN_2, logicalRight);
  currentLeft = logicalLeft;
  currentRight = logicalRight;
  driveActive = logicalLeft != 0 || logicalRight != 0;
  lastDriveCommandMs = millis();
}

void motorsStop() {
  ledcWrite(MOTOR_R_PIN_1, 0);
  ledcWrite(MOTOR_R_PIN_2, 0);
  ledcWrite(MOTOR_L_PIN_1, 0);
  ledcWrite(MOTOR_L_PIN_2, 0);
  currentLeft = 0;
  currentRight = 0;
  driveActive = false;
}

void motorsForward(int speed, int trim) {
  motorsSet(constrain(speed + trim, 0, 255), constrain(speed - trim, 0, 255));
}
void motorsBackward(int speed, int trim) {
  motorsSet(-constrain(speed + trim, 0, 255), -constrain(speed - trim, 0, 255));
}
void motorsLeft(int speed) { motorsSet(-speed, speed); }
void motorsRight(int speed) { motorsSet(speed, -speed); }

bool startCamera() {
  if (cameraReady) return true;
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  if (psramFound()) {
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
  if (err != ESP_OK) {
    Serial.printf("[CAM] init failed: 0x%x\n", err);
    cameraReady = false;
    cameraSensor = nullptr;
    return false;
  }
  cameraSensor = esp_camera_sensor_get();
  if (cameraSensor) cameraSensor->set_vflip(cameraSensor, 1);
  cameraReady = true;
  Serial.println("[CAM] OV2640 ready");
  return true;
}

void stopStreamServer() {
  if (streamHttpd) {
    httpd_stop(streamHttpd);
    streamHttpd = nullptr;
  }
  streamReady = false;
}

bool retryCamera() {
  // Camera reinitialization can block long enough to violate the drive deadman loop. Stop first.
  motorsStop();
  stopStreamServer();
  if (cameraReady) {
    esp_camera_deinit();
    cameraReady = false;
    cameraSensor = nullptr;
    delay(100);
  }
  return startCamera();
}

String statusJson(bool includeKey) {
  String ip = wifiConnected ? WiFi.localIP().toString() : "";
  String ssid = wifiConnected ? WiFi.SSID() : wifiSsid;
  long rssi = wifiConnected ? WiFi.RSSI() : 0;
  String json = "{";
  json += "\"profile\":\"" + String(HARDWARE_PROFILE) + "\",";
  json += "\"board\":\"AI Thinker ESP32-CAM\",";
  json += "\"protocol\":" + String(PROTOCOL_VERSION) + ",";
  json += "\"fw\":\"" + String(FW_VERSION) + "\",";
  json += "\"mode\":\"HYBRID\",";
  json += "\"ip\":\"" + ip + "\",";
  json += "\"ssid\":\"" + ssid + "\",";
  json += "\"rssi\":" + String(rssi) + ",";
  json += "\"speed\":" + String(currentSpeed) + ",";
  json += "\"trim\":" + String(currentTrim) + ",";
  json += "\"left_pwm\":" + String(currentLeft) + ",";
  json += "\"right_pwm\":" + String(currentRight) + ",";
  json += "\"motor_swap\":" + String(motorSwap ? "true" : "false") + ",";
  json += "\"invert_left\":" + String(invertLeft ? "true" : "false") + ",";
  json += "\"invert_right\":" + String(invertRight ? "true" : "false") + ",";
  json += "\"camera\":" + String(cameraReady ? "true" : "false") + ",";
  json += "\"http_ready\":" + String(httpReady ? "true" : "false") + ",";
  json += "\"stream_ready\":" + String(streamReady ? "true" : "false") + ",";
  json += "\"stream_fps\":" + String(streamFps) + ",";
  json += "\"ota\":true,";
  json += "\"heap\":" + String(ESP.getFreeHeap());
  if (includeKey) json += ",\"ota_key\":\"" + otaKey + "\"";
  json += "}";
  return json;
}

bool headerMatches(httpd_req_t* req, const char* name, const String& expected) {
  size_t len = httpd_req_get_hdr_value_len(req, name);
  if (len == 0 || len >= 96) return false;
  char value[96];
  if (httpd_req_get_hdr_value_str(req, name, value, sizeof(value)) != ESP_OK) return false;
  return expected == String(value);
}
bool controlAuthorized(httpd_req_t* req) { return headerMatches(req, "X-ESP32-Control-Key", otaKey); }
bool otaAuthorized(httpd_req_t* req) { return headerMatches(req, "X-ESP32-OTA-Key", otaKey); }
esp_err_t sendUnauthorized(httpd_req_t* req) {
  httpd_resp_set_status(req, "401 Unauthorized");
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"unauthorized\"}");
}

static const char* STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=frame";
static const char* STREAM_BOUNDARY = "\r\n--frame\r\n";
static const char* STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static esp_err_t streamHandler(httpd_req_t* req) {
  if (!cameraReady) return ESP_FAIL;
  esp_err_t res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  while (true) {
    uint32_t started = millis();
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) return ESP_FAIL;
    char header[64];
    size_t headerLen = snprintf(header, sizeof(header), STREAM_PART, (unsigned)fb->len);
    res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, header, headerLen);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len);
    esp_camera_fb_return(fb);
    if (res != ESP_OK) break;
    int fps = constrain(streamFps, 5, 20);
    uint32_t frameMs = 1000U / fps;
    uint32_t elapsed = millis() - started;
    if (elapsed < frameMs) delay(frameMs - elapsed);
  }
  return res;
}

static esp_err_t captureHandler(httpd_req_t* req) {
  if (!cameraReady) return httpd_resp_send_500(req);
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) return httpd_resp_send_500(req);
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t res = httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

static esp_err_t infoHandler(httpd_req_t* req) {
  if (!controlAuthorized(req)) return sendUnauthorized(req);
  String json = statusJson(false);
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, json.c_str(), json.length());
}

static esp_err_t actionHandler(httpd_req_t* req) {
  if (!controlAuthorized(req)) return sendUnauthorized(req);
  size_t len = httpd_req_get_url_query_len(req) + 1;
  if (len <= 1) return httpd_resp_sendstr(req, "OK");
  char* query = (char*)malloc(len);
  if (!query) return httpd_resp_send_500(req);
  if (httpd_req_get_url_query_str(req, query, len) != ESP_OK) {
    free(query);
    return httpd_resp_send_500(req);
  }
  char value[48];
  if (httpd_query_key_value(query, "light", value, sizeof(value)) == ESP_OK) ledcWrite(FLASH_LED_PIN, constrain(atoi(value), 0, 255));
  if (httpd_query_key_value(query, "speed", value, sizeof(value)) == ESP_OK) currentSpeed = constrain(atoi(value), 50, 255);
  if (httpd_query_key_value(query, "trim", value, sizeof(value)) == ESP_OK) currentTrim = constrain(atoi(value), -50, 50);
  if (httpd_query_key_value(query, "stream_fps", value, sizeof(value)) == ESP_OK) streamFps = constrain(atoi(value), 5, 20);
  if (httpd_query_key_value(query, "stream_quality", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_quality(cameraSensor, constrain(atoi(value), 4, 20));
  if (httpd_query_key_value(query, "stream_size", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_framesize(cameraSensor, (framesize_t)atoi(value));
  if (httpd_query_key_value(query, "brightness", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_brightness(cameraSensor, constrain(atoi(value), -2, 2));
  if (httpd_query_key_value(query, "contrast", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_contrast(cameraSensor, constrain(atoi(value), -2, 2));
  if (httpd_query_key_value(query, "saturation", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_saturation(cameraSensor, constrain(atoi(value), -2, 2));
  if (httpd_query_key_value(query, "hmirror", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_hmirror(cameraSensor, atoi(value) != 0);
  if (httpd_query_key_value(query, "vflip", value, sizeof(value)) == ESP_OK && cameraSensor) cameraSensor->set_vflip(cameraSensor, atoi(value) != 0);

  bool configChanged = false;
  if (httpd_query_key_value(query, "motor_swap", value, sizeof(value)) == ESP_OK) { motorSwap = atoi(value) != 0; configChanged = true; }
  if (httpd_query_key_value(query, "invert_left", value, sizeof(value)) == ESP_OK) { invertLeft = atoi(value) != 0; configChanged = true; }
  if (httpd_query_key_value(query, "invert_right", value, sizeof(value)) == ESP_OK) { invertRight = atoi(value) != 0; configChanged = true; }
  if (configChanged) { motorsStop(); saveMotorConfig(); }

  char leftValue[16];
  char rightValue[16];
  bool hasLeft = httpd_query_key_value(query, "left", leftValue, sizeof(leftValue)) == ESP_OK;
  bool hasRight = httpd_query_key_value(query, "right", rightValue, sizeof(rightValue)) == ESP_OK;
  if (hasLeft && hasRight) motorsSet(atoi(leftValue), atoi(rightValue));

  if (httpd_query_key_value(query, "camera", value, sizeof(value)) == ESP_OK && !strcmp(value, "retry")) {
    if (retryCamera()) startStreamServer();
  }

  if (httpd_query_key_value(query, "go", value, sizeof(value)) == ESP_OK) {
    if (!strcmp(value, "STATUS")) {
      String json = statusJson(false);
      free(query);
      httpd_resp_set_type(req, "application/json");
      return httpd_resp_send(req, json.c_str(), json.length());
    } else if (!strcmp(value, "REBOOT")) {
      motorsStop(); pendingRestart = true; restartAtMs = millis() + 500;
    } else if (!strcmp(value, "forward")) motorsForward(currentSpeed, currentTrim);
    else if (!strcmp(value, "backward")) motorsBackward(currentSpeed, currentTrim);
    else if (!strcmp(value, "left")) motorsLeft(currentSpeed);
    else if (!strcmp(value, "right")) motorsRight(currentSpeed);
    else if (!strcmp(value, "stop")) motorsStop();
  }
  free(query);
  httpd_resp_set_type(req, "text/plain");
  return httpd_resp_sendstr(req, "OK");
}

static esp_err_t otaUploadHandler(httpd_req_t* req) {
  if (!otaAuthorized(req)) return sendUnauthorized(req);
  if (req->content_len <= 0) {
    httpd_resp_set_status(req, "400 Bad Request");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"empty_firmware\"}");
  }
  motorsStop();
  if (!Update.begin(req->content_len, U_FLASH)) {
    httpd_resp_set_status(req, "500 Internal Server Error");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"update_begin_failed\"}");
  }
  uint8_t buffer[2048];
  int remaining = req->content_len;
  while (remaining > 0) {
    int received = httpd_req_recv(req, (char*)buffer, min(remaining, (int)sizeof(buffer)));
    if (received == HTTPD_SOCK_ERR_TIMEOUT) continue;
    if (received <= 0) {
      Update.abort();
      httpd_resp_set_status(req, "500 Internal Server Error");
      return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"receive_failed\"}");
    }
    if (Update.write(buffer, received) != (size_t)received) {
      Update.abort();
      httpd_resp_set_status(req, "500 Internal Server Error");
      return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"flash_write_failed\"}");
    }
    remaining -= received;
  }
  if (!Update.end(true)) {
    httpd_resp_set_status(req, "500 Internal Server Error");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"verification_failed\"}");
  }
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Connection", "close");
  esp_err_t result = httpd_resp_sendstr(req, "{\"ok\":true,\"transport\":\"http\",\"rebooting\":true}");
  pendingRestart = true;
  restartAtMs = millis() + 900;
  return result;
}

bool startControlServer() {
  if (controlHttpd) return true;
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  config.ctrl_port = 32767;
  config.stack_size = 8192;
  if (httpd_start(&controlHttpd, &config) != ESP_OK) {
    controlHttpd = nullptr;
    httpReady = false;
    Serial.println("[HTTP] port 80 start failed");
    return false;
  }
  httpd_uri_t infoUri = { .uri = "/api/info", .method = HTTP_GET, .handler = infoHandler, .user_ctx = nullptr };
  httpd_uri_t otaUri = { .uri = "/api/ota", .method = HTTP_POST, .handler = otaUploadHandler, .user_ctx = nullptr };
  httpd_uri_t actionUri = { .uri = "/action", .method = HTTP_GET, .handler = actionHandler, .user_ctx = nullptr };
  httpd_uri_t captureUri = { .uri = "/capture", .method = HTTP_GET, .handler = captureHandler, .user_ctx = nullptr };
  httpd_register_uri_handler(controlHttpd, &infoUri);
  httpd_register_uri_handler(controlHttpd, &otaUri);
  httpd_register_uri_handler(controlHttpd, &actionUri);
  httpd_register_uri_handler(controlHttpd, &captureUri);
  httpReady = true;
  Serial.println("[HTTP] status/control/OTA :80");
  return true;
}

bool startStreamServer() {
  if (!cameraReady) return false;
  if (streamHttpd) return true;
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 81;
  config.ctrl_port = 32766;
  config.stack_size = 8192;
  if (httpd_start(&streamHttpd, &config) != ESP_OK) {
    streamHttpd = nullptr;
    streamReady = false;
    Serial.println("[HTTP] stream :81 start failed");
    return false;
  }
  httpd_uri_t streamUri = { .uri = "/stream", .method = HTTP_GET, .handler = streamHandler, .user_ctx = nullptr };
  httpd_register_uri_handler(streamHttpd, &streamUri);
  streamReady = true;
  Serial.println("[HTTP] MJPEG :81/stream");
  return true;
}

bool connectWifiAndStartServices(bool announceBt) {
  // Wi-Fi association can block for seconds. Never allow the last drive PWM to survive it.
  motorsStop();
  loadWifiCredentials();
  if (wifiSsid.isEmpty()) {
    if (announceBt) SerialBT.println("ERR:NO_WIFI_CREDENTIALS");
    return false;
  }
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  Serial.printf("[WiFi] connecting to %s\n", wifiSsid.c_str());
  uint32_t started = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - started < 10000) delay(100);
  if (WiFi.status() != WL_CONNECTED) {
    wifiConnected = false;
    if (announceBt) SerialBT.println("ERR:WIFI_CONNECT_FAILED");
    Serial.println("[WiFi] initial association failed; auto-reconnect remains armed and Bluetooth control is active");
    return false;
  }
  wifiConnected = true;
  // Critical: OTA/status starts even if the camera is missing or unhealthy.
  startControlServer();
  if (startCamera()) startStreamServer();
  String ip = WiFi.localIP().toString();
  Serial.printf("[WiFi] connected: %s\n", ip.c_str());
  if (announceBt) SerialBT.println("OK:WIFI_CONNECTED:" + ip);
  return true;
}

void processBluetoothCommand(String cmd) {
  cmd.trim();
  if (cmd.isEmpty()) return;
  if (cmd == "STATUS") { SerialBT.println(statusJson(true)); return; }
  if (cmd.startsWith("W:")) {
    String rest = cmd.substring(2);
    int comma = rest.indexOf(',');
    if (comma < 0) { SerialBT.println("ERR:FORMAT"); return; }
    String ssid = rest.substring(0, comma);
    String pass = rest.substring(comma + 1);
    if (ssid.isEmpty()) { SerialBT.println("ERR:EMPTY_SSID"); return; }
    saveWifiCredentials(ssid, pass);
    SerialBT.println("OK:WIFI_SAVED:" + ssid);
    return;
  }
  if (cmd == "X") { connectWifiAndStartServices(true); return; }
  if (cmd == "CAM_RETRY") {
    bool ok = retryCamera();
    if (ok) startStreamServer();
    SerialBT.println(ok ? "OK:CAMERA_READY" : "ERR:CAMERA_INIT_FAILED");
    return;
  }
  if (cmd == "REBOOT") {
    motorsStop();
    SerialBT.println("OK:REBOOTING");
    pendingRestart = true;
    restartAtMs = millis() + 500;
    return;
  }
  if (cmd.startsWith("V")) { currentSpeed = constrain(cmd.substring(1).toInt(), 50, 255); return; }
  if (cmd.startsWith("T")) { currentTrim = constrain(cmd.substring(1).toInt(), -50, 50); return; }
  if (cmd.startsWith("H")) { ledcWrite(FLASH_LED_PIN, constrain(cmd.substring(1).toInt(), 0, 255)); return; }
  if (cmd.startsWith("M:")) {
    String rest = cmd.substring(2);
    int comma = rest.indexOf(',');
    if (comma >= 0) motorsSet(rest.substring(0, comma).toInt(), rest.substring(comma + 1).toInt());
    return;
  }
  if (cmd.startsWith("C:")) {
    String rest = cmd.substring(2);
    int c1 = rest.indexOf(',');
    int c2 = rest.indexOf(',', c1 + 1);
    if (c1 < 0 || c2 < 0) return;
    motorsStop();
    motorSwap = rest.substring(0, c1).toInt() != 0;
    invertLeft = rest.substring(c1 + 1, c2).toInt() != 0;
    invertRight = rest.substring(c2 + 1).toInt() != 0;
    saveMotorConfig();
    SerialBT.println("OK:MOTOR_CONFIG");
    return;
  }
  if (cmd == "F") motorsForward(currentSpeed, currentTrim);
  else if (cmd == "B") motorsBackward(currentSpeed, currentTrim);
  else if (cmd == "L") motorsLeft(currentSpeed);
  else if (cmd == "R") motorsRight(currentSpeed);
  else if (cmd == "S") motorsStop();
  else SerialBT.println("ERR:UNKNOWN_CMD:" + cmd);
}

void handleSerialCommand(String cmd) {
  cmd.trim();
  if (cmd.startsWith("WIFI:")) {
    String rest = cmd.substring(5);
    int comma = rest.indexOf(',');
    if (comma > 0) {
      saveWifiCredentials(rest.substring(0, comma), rest.substring(comma + 1));
      Serial.println("OK:WIFI_SAVED");
    }
  } else if (cmd == "STATUS") Serial.println(statusJson(true));
  else if (cmd == "STOP") motorsStop();
  else if (cmd == "CAM_RETRY") {
    bool ok = retryCamera();
    if (ok) startStreamServer();
    Serial.println(ok ? "OK:CAMERA_READY" : "ERR:CAMERA_INIT_FAILED");
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  setupMotorPwm();
  motorsStop();
  loadMotorConfig();
  loadWifiCredentials();
  otaKey = loadOrCreateOtaKey();
  bool btReady = SerialBT.begin("ESP32_CAM_RC");
  Serial.println("\n===== ESP32-CAM RC v4.0.0 =====");
  Serial.println("Baseline: original working v3.0 motor/camera profile");
  Serial.printf("Bluetooth: %s\n", btReady ? "ready" : "FAILED");
  Serial.println("Control: Bluetooth always on");
  Serial.println("Vision/OTA: Wi-Fi in parallel");
  if (!wifiSsid.isEmpty()) connectWifiAndStartServices(false);
  Serial.println("=================================\n");
}

void loop() {
  if (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\n') { processBluetoothCommand(btBuffer); btBuffer = ""; }
    else if (c != '\r') { btBuffer += c; if (btBuffer.length() > 160) btBuffer = ""; }
  }
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') { handleSerialCommand(serialBuffer); serialBuffer = ""; }
    else if (c != '\r') { serialBuffer += c; if (serialBuffer.length() > 160) serialBuffer = ""; }
  }
  if (driveActive && millis() - lastDriveCommandMs > DRIVE_DEADMAN_MS) motorsStop();

  const bool stationNowConnected = WiFi.status() == WL_CONNECTED;
  if (!stationNowConnected && wifiConnected) {
    wifiConnected = false;
    Serial.println("[WiFi] link lost; Bluetooth control unaffected, waiting for auto-reconnect");
  } else if (stationNowConnected && !wifiConnected) {
    wifiConnected = true;
    // HTTP servers bind to all interfaces, so an existing server remains usable after reconnect.
    // If this is a late first association, create services now.
    startControlServer();
    if (startCamera()) startStreamServer();
    Serial.printf("[WiFi] link restored: %s\n", WiFi.localIP().toString().c_str());
  }

  if (pendingRestart && millis() >= restartAtMs) {
    motorsStop();
    delay(50);
    ESP.restart();
  }
  delay(5);
}
