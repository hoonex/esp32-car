// ESP32 Car firmware v3.3.0
// Target: AI Thinker ESP32-CAM + OV2640 + 2WD chassis + L298N four-input motor driver
// Motor wiring profile: LEFT GPIO13/12, RIGHT GPIO14/15, flash LED GPIO4

#include <Arduino.h>
#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"
#include "img_converters.h"
#include "BluetoothSerial.h"
#include <Preferences.h>
#include <ArduinoOTA.h>
#include <Update.h>
#include <esp_system.h>

// ===== AI Thinker ESP32-CAM camera pins =====
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

// ===== 2WD L298N profile =====
static const int MOTOR_R_PIN_1 = 14;
static const int MOTOR_R_PIN_2 = 15;
static const int MOTOR_L_PIN_1 = 13;
static const int MOTOR_L_PIN_2 = 12;
static const int FLASH_LED_PIN = 4;

// Keep camera XCLK on LEDC channel 0. Motor PWM is explicitly placed on 4..7.
static const int CH_FLASH = 2;
static const int CH_L1 = 4;
static const int CH_L2 = 5;
static const int CH_R1 = 6;
static const int CH_R2 = 7;
static const int MOTOR_PWM_FREQ = 18000;
static const int LED_PWM_FREQ = 5000;
static const int PWM_BITS = 8;

static const char* FW_VERSION = "3.3.0";
static const int PROTOCOL_VERSION = 2;
static const char* HARDWARE_PROFILE = "AI_THINKER_ESP32_CAM_2WD_L298N";
static const char* UPDATE_AP_SSID = "ESP32-CAR-UPDATE";
static const char* UPDATE_AP_PASSWORD = "esp32car";
static const uint32_t DRIVE_DEADMAN_MS = 450;
static const int DEFAULT_STREAM_FPS = 12;

Preferences preferences;
BluetoothSerial SerialBT;
String otaKey;
String wifiSsid;
String wifiPass;
String btBuffer;
String serialBuffer;

bool wifiOnline = false;
bool recoveryApActive = false;
bool cameraStarted = false;
bool serversStarted = false;
bool otaStarted = false;
bool pendingRestart = false;
uint32_t restartAtMs = 0;
volatile bool btEmergencyStopRequested = false;
volatile bool sppClientConnected = false;

bool motorSwap = false;
bool invertLeft = false;
bool invertRight = false;
int currentSpeed = 190;
int currentTrim = 0;
int currentLogicalLeft = 0;
int currentLogicalRight = 0;
uint32_t lastDriveCommandMs = 0;
uint32_t deadmanTrips = 0;
bool driveActive = false;
int streamFps = DEFAULT_STREAM_FPS;

httpd_handle_t cameraHttpd = NULL;
httpd_handle_t streamHttpd = NULL;
sensor_t* cameraSensor = NULL;

// ===== Preferences =====
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
    char buf[17];
    snprintf(
      buf,
      sizeof(buf),
      "%08lX%08lX",
      (unsigned long)esp_random(),
      (unsigned long)(ESP.getEfuseMac() & 0xFFFFFFFFULL)
    );
    key = String(buf);
    preferences.putString("key", key);
  }
  preferences.end();
  return key;
}

// ===== Motor driver =====
void setupMotorPwm() {
  ledcAttachChannel(FLASH_LED_PIN, LED_PWM_FREQ, PWM_BITS, CH_FLASH);
  ledcAttachChannel(MOTOR_L_PIN_1, MOTOR_PWM_FREQ, PWM_BITS, CH_L1);
  ledcAttachChannel(MOTOR_L_PIN_2, MOTOR_PWM_FREQ, PWM_BITS, CH_L2);
  ledcAttachChannel(MOTOR_R_PIN_1, MOTOR_PWM_FREQ, PWM_BITS, CH_R1);
  ledcAttachChannel(MOTOR_R_PIN_2, MOTOR_PWM_FREQ, PWM_BITS, CH_R2);
  ledcWrite(FLASH_LED_PIN, 0);
  ledcWrite(MOTOR_L_PIN_1, 0);
  ledcWrite(MOTOR_L_PIN_2, 0);
  ledcWrite(MOTOR_R_PIN_1, 0);
  ledcWrite(MOTOR_R_PIN_2, 0);
}

void writeLeftPhysical(int value) {
  value = constrain(value, -255, 255);
  if (value >= 0) {
    ledcWrite(MOTOR_L_PIN_1, value);
    ledcWrite(MOTOR_L_PIN_2, 0);
  } else {
    ledcWrite(MOTOR_L_PIN_1, 0);
    ledcWrite(MOTOR_L_PIN_2, -value);
  }
}

void writeRightPhysical(int value) {
  value = constrain(value, -255, 255);
  // This side is electrically mirrored on the common AI Thinker 2WD/L298N harness.
  if (value >= 0) {
    ledcWrite(MOTOR_R_PIN_1, 0);
    ledcWrite(MOTOR_R_PIN_2, value);
  } else {
    ledcWrite(MOTOR_R_PIN_1, -value);
    ledcWrite(MOTOR_R_PIN_2, 0);
  }
}

void motorsStop() {
  ledcWrite(MOTOR_L_PIN_1, 0);
  ledcWrite(MOTOR_L_PIN_2, 0);
  ledcWrite(MOTOR_R_PIN_1, 0);
  ledcWrite(MOTOR_R_PIN_2, 0);
  currentLogicalLeft = 0;
  currentLogicalRight = 0;
  driveActive = false;
}

void motorsSet(int logicalLeft, int logicalRight) {
  logicalLeft = constrain(logicalLeft, -255, 255);
  logicalRight = constrain(logicalRight, -255, 255);
  currentLogicalLeft = logicalLeft;
  currentLogicalRight = logicalRight;

  int physicalLeft = logicalLeft;
  int physicalRight = logicalRight;
  if (motorSwap) {
    int temp = physicalLeft;
    physicalLeft = physicalRight;
    physicalRight = temp;
  }
  if (invertLeft) physicalLeft = -physicalLeft;
  if (invertRight) physicalRight = -physicalRight;

  writeLeftPhysical(physicalLeft);
  writeRightPhysical(physicalRight);
  lastDriveCommandMs = millis();
  driveActive = (logicalLeft != 0 || logicalRight != 0);
}

void motorsForward(int speed, int trim) {
  motorsSet(constrain(speed + trim, 0, 255), constrain(speed - trim, 0, 255));
}

void motorsBackward(int speed, int trim) {
  motorsSet(-constrain(speed + trim, 0, 255), -constrain(speed - trim, 0, 255));
}

void motorsLeft(int speed) { motorsSet(-speed, speed); }
void motorsRight(int speed) { motorsSet(speed, -speed); }

bool controlAuthorized(httpd_req_t* req) {
  size_t len = httpd_req_get_hdr_value_len(req, "X-ESP32-Control-Key");
  if (len == 0 || len > 64) return false;
  char value[65];
  if (httpd_req_get_hdr_value_str(req, "X-ESP32-Control-Key", value, sizeof(value)) != ESP_OK) return false;
  return otaKey == String(value);
}

esp_err_t sendUnauthorized(httpd_req_t* req) {
  httpd_resp_set_status(req, "401 Unauthorized");
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"unauthorized\"}");
}

// ===== Camera =====
void ensureCamera() {
  if (cameraStarted) return;

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
    config.jpeg_quality = 10;
    config.fb_count = 2;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_LATEST;
  } else {
    config.frame_size = FRAMESIZE_QQVGA;
    config.jpeg_quality = 12;
    config.fb_count = 1;
    config.fb_location = CAMERA_FB_IN_DRAM;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[CAM] init failed: 0x%x\n", err);
    return;
  }

  cameraSensor = esp_camera_sensor_get();
  if (cameraSensor) cameraSensor->set_vflip(cameraSensor, 1);
  cameraStarted = true;
  Serial.println("[CAM] AI Thinker OV2640 ready");
}

static const char* STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=frame";
static const char* STREAM_BOUNDARY = "\r\n--frame\r\n";
static const char* STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static esp_err_t streamHandler(httpd_req_t* req) {
  if (!controlAuthorized(req)) return sendUnauthorized(req);
  if (!cameraStarted) return ESP_FAIL;
  esp_err_t res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");

  while (true) {
    uint32_t frameStartedAt = millis();
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) return ESP_FAIL;

    char header[64];
    size_t headerLen = snprintf(header, sizeof(header), STREAM_PART, (unsigned)fb->len);
    if (
      httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY)) != ESP_OK ||
      httpd_resp_send_chunk(req, header, headerLen) != ESP_OK ||
      httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len) != ESP_OK
    ) {
      esp_camera_fb_return(fb);
      break;
    }
    esp_camera_fb_return(fb);

    const uint32_t frameBudgetMs = 1000U / (uint32_t)constrain(streamFps, 5, 20);
    uint32_t elapsed = millis() - frameStartedAt;
    if (elapsed < frameBudgetMs) delay(frameBudgetMs - elapsed);
  }
  return ESP_OK;
}

static esp_err_t captureHandler(httpd_req_t* req) {
  if (!controlAuthorized(req)) return sendUnauthorized(req);
  if (!cameraStarted) return httpd_resp_send_500(req);
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) return httpd_resp_send_500(req);
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Content-Disposition", "inline; filename=esp32-car.jpg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t result = httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return result;
}

String statusJson(bool includeKey) {
  bool stationConnected = WiFi.status() == WL_CONNECTED;
  String ip = stationConnected ? WiFi.localIP().toString() : (recoveryApActive ? WiFi.softAPIP().toString() : String("0.0.0.0"));
  String ssid = stationConnected ? WiFi.SSID() : (recoveryApActive ? String(UPDATE_AP_SSID) : String(""));
  long rssi = stationConnected ? WiFi.RSSI() : 0;
  String mode = stationConnected ? "WIFI_STA" : (recoveryApActive ? "RECOVERY_AP" : "BT");
  String json = "{";
  json += "\"protocol\":" + String(PROTOCOL_VERSION) + ",";
  json += "\"mode\":\"" + mode + "\",";
  json += "\"ip\":\"" + ip + "\",";
  json += "\"ssid\":\"" + ssid + "\",";
  json += "\"rssi\":" + String(rssi) + ",";
  json += "\"fw\":\"" + String(FW_VERSION) + "\",";
  json += "\"board\":\"AI Thinker ESP32-CAM\",";
  json += "\"profile\":\"" + String(HARDWARE_PROFILE) + "\",";
  json += "\"motor_swap\":" + String(motorSwap ? "true" : "false") + ",";
  json += "\"invert_left\":" + String(invertLeft ? "true" : "false") + ",";
  json += "\"invert_right\":" + String(invertRight ? "true" : "false") + ",";
  json += "\"left_pwm\":" + String(currentLogicalLeft) + ",";
  json += "\"right_pwm\":" + String(currentLogicalRight) + ",";
  json += "\"heap\":" + String(ESP.getFreeHeap()) + ",";
  json += "\"min_heap\":" + String(ESP.getMinFreeHeap()) + ",";
  json += "\"psram_free\":" + String(psramFound() ? ESP.getFreePsram() : 0) + ",";
  json += "\"uptime_ms\":" + String(millis()) + ",";
  json += "\"deadman_ms\":" + String(DRIVE_DEADMAN_MS) + ",";
  json += "\"deadman_trips\":" + String(deadmanTrips) + ",";
  json += "\"stream_fps\":" + String(streamFps) + ",";
  json += "\"camera\":" + String(cameraStarted ? "true" : "false") + ",";
  json += "\"spp_connected\":" + String(sppClientConnected ? "true" : "false") + ",";
  json += "\"ota\":true";
  if (includeKey) json += ",\"ota_key\":\"" + otaKey + "\"";
  json += "}";
  return json;
}

static esp_err_t actionHandler(httpd_req_t* req) {
  if (!controlAuthorized(req)) return sendUnauthorized(req);

  size_t queryLen = httpd_req_get_url_query_len(req) + 1;
  if (queryLen <= 1) {
    httpd_resp_set_type(req, "text/plain");
    return httpd_resp_sendstr(req, "OK");
  }

  char* query = (char*)malloc(queryLen);
  if (!query) return httpd_resp_send_500(req);
  if (httpd_req_get_url_query_str(req, query, queryLen) != ESP_OK) {
    free(query);
    return httpd_resp_send_500(req);
  }

  char value[48];
  bool configChanged = false;

  if (httpd_query_key_value(query, "light", value, sizeof(value)) == ESP_OK) {
    ledcWrite(FLASH_LED_PIN, constrain(atoi(value), 0, 255));
  }

  if (httpd_query_key_value(query, "speed", value, sizeof(value)) == ESP_OK) {
    currentSpeed = constrain(atoi(value), 50, 255);
  }
  if (httpd_query_key_value(query, "trim", value, sizeof(value)) == ESP_OK) {
    currentTrim = constrain(atoi(value), -50, 50);
  }

  char leftValue[16], rightValue[16];
  bool hasLeft = httpd_query_key_value(query, "left", leftValue, sizeof(leftValue)) == ESP_OK;
  bool hasRight = httpd_query_key_value(query, "right", rightValue, sizeof(rightValue)) == ESP_OK;
  if (hasLeft && hasRight) {
    motorsSet(atoi(leftValue), atoi(rightValue));
  }

  if (httpd_query_key_value(query, "motor_swap", value, sizeof(value)) == ESP_OK) {
    motorSwap = atoi(value) != 0;
    configChanged = true;
  }
  if (httpd_query_key_value(query, "invert_left", value, sizeof(value)) == ESP_OK) {
    invertLeft = atoi(value) != 0;
    configChanged = true;
  }
  if (httpd_query_key_value(query, "invert_right", value, sizeof(value)) == ESP_OK) {
    invertRight = atoi(value) != 0;
    configChanged = true;
  }
  if (configChanged) {
    motorsStop();
    saveMotorConfig();
  }

  if (cameraSensor) {
    if (httpd_query_key_value(query, "stream_quality", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_quality(cameraSensor, constrain(atoi(value), 4, 20));
    if (httpd_query_key_value(query, "stream_size", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_framesize(cameraSensor, (framesize_t)constrain(atoi(value), 0, 8));
    if (httpd_query_key_value(query, "stream_fps", value, sizeof(value)) == ESP_OK)
      streamFps = constrain(atoi(value), 5, 20);
    if (httpd_query_key_value(query, "brightness", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_brightness(cameraSensor, constrain(atoi(value), -2, 2));
    if (httpd_query_key_value(query, "contrast", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_contrast(cameraSensor, constrain(atoi(value), -2, 2));
    if (httpd_query_key_value(query, "saturation", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_saturation(cameraSensor, constrain(atoi(value), -2, 2));
    if (httpd_query_key_value(query, "hmirror", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_hmirror(cameraSensor, atoi(value) != 0);
    if (httpd_query_key_value(query, "vflip", value, sizeof(value)) == ESP_OK)
      cameraSensor->set_vflip(cameraSensor, atoi(value) != 0);
  }

  if (httpd_query_key_value(query, "go", value, sizeof(value)) == ESP_OK) {
    if (!strcmp(value, "STATUS")) {
      String json = statusJson(false);
      free(query);
      httpd_resp_set_type(req, "application/json");
      return httpd_resp_send(req, json.c_str(), json.length());
    }
    if (!strcmp(value, "REBOOT")) {
      motorsStop();
      pendingRestart = true;
      restartAtMs = millis() + 500;
    } else if (!strcmp(value, "forward")) {
      motorsForward(currentSpeed, currentTrim);
    } else if (!strcmp(value, "backward")) {
      motorsBackward(currentSpeed, currentTrim);
    } else if (!strcmp(value, "left")) {
      motorsLeft(currentSpeed);
    } else if (!strcmp(value, "right")) {
      motorsRight(currentSpeed);
    } else if (!strcmp(value, "stop")) {
      motorsStop();
    }
  }

  free(query);
  httpd_resp_set_type(req, "text/plain");
  return httpd_resp_sendstr(req, "OK");
}

bool otaAuthorized(httpd_req_t* req) {
  size_t len = httpd_req_get_hdr_value_len(req, "X-ESP32-OTA-Key");
  if (len == 0 || len > 64) return false;
  char value[65];
  if (httpd_req_get_hdr_value_str(req, "X-ESP32-OTA-Key", value, sizeof(value)) != ESP_OK) return false;
  return otaKey == String(value);
}

static esp_err_t otaInfoHandler(httpd_req_t* req) {
  if (!controlAuthorized(req)) return sendUnauthorized(req);
  String json = statusJson(false);
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, json.c_str(), json.length());
}

static esp_err_t otaUploadHandler(httpd_req_t* req) {
  if (!otaAuthorized(req)) {
    httpd_resp_set_status(req, "401 Unauthorized");
    httpd_resp_set_type(req, "application/json");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"unauthorized\"}");
  }
  if (req->content_len <= 0) {
    httpd_resp_set_status(req, "400 Bad Request");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"empty_firmware\"}");
  }

  motorsStop();
  if (!Update.begin(req->content_len, U_FLASH)) {
    httpd_resp_set_status(req, "500 Internal Server Error");
    return httpd_resp_sendstr(req, "{\"ok\":false,\"error\":\"update_begin_failed\"}");
  }

  uint8_t buffer[4096];
  int remaining = req->content_len;
  while (remaining > 0) {
    int received = httpd_req_recv(req, (char*)buffer, min(remaining, (int)sizeof(buffer)));
    if (received == HTTPD_SOCK_ERR_TIMEOUT) continue;
    if (received <= 0 || Update.write(buffer, received) != (size_t)received) {
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
  httpd_resp_sendstr(req, "{\"ok\":true,\"rebooting\":true}");
  pendingRestart = true;
  restartAtMs = millis() + 1200;
  return ESP_OK;
}

static esp_err_t indexHandler(httpd_req_t* req) {
  const char* page =
    "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<style>body{background:#080b10;color:#fff;font-family:sans-serif;margin:24px}</style></head>"
    "<body><h2>ESP32 Car 3.3</h2><p>AI Thinker ESP32-CAM · 2WD L298N</p>"
    "<p>Control, camera and diagnostics require the per-device key delivered over Bluetooth. Use the Android app.</p></body></html>";
  httpd_resp_set_type(req, "text/html");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_sendstr(req, page);
}

void startServers() {
  if (serversStarted) return;
  ensureCamera();
  if (!cameraStarted) return;

  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  config.ctrl_port = 32767;
  if (httpd_start(&cameraHttpd, &config) == ESP_OK) {
    httpd_uri_t indexUri = { .uri = "/", .method = HTTP_GET, .handler = indexHandler, .user_ctx = NULL };
    httpd_uri_t actionUri = { .uri = "/action", .method = HTTP_GET, .handler = actionHandler, .user_ctx = NULL };
    httpd_uri_t captureUri = { .uri = "/capture", .method = HTTP_GET, .handler = captureHandler, .user_ctx = NULL };
    httpd_uri_t infoUri = { .uri = "/api/info", .method = HTTP_GET, .handler = otaInfoHandler, .user_ctx = NULL };
    httpd_uri_t otaUri = { .uri = "/api/ota", .method = HTTP_POST, .handler = otaUploadHandler, .user_ctx = NULL };
    httpd_register_uri_handler(cameraHttpd, &indexUri);
    httpd_register_uri_handler(cameraHttpd, &actionUri);
    httpd_register_uri_handler(cameraHttpd, &captureUri);
    httpd_register_uri_handler(cameraHttpd, &infoUri);
    httpd_register_uri_handler(cameraHttpd, &otaUri);
  }

  httpd_config_t streamConfig = HTTPD_DEFAULT_CONFIG();
  streamConfig.server_port = 81;
  streamConfig.ctrl_port = 32766;
  if (httpd_start(&streamHttpd, &streamConfig) == ESP_OK) {
    httpd_uri_t streamUri = { .uri = "/stream", .method = HTTP_GET, .handler = streamHandler, .user_ctx = NULL };
    httpd_register_uri_handler(streamHttpd, &streamUri);
  }
  serversStarted = (cameraHttpd != NULL && streamHttpd != NULL);
}

void startOta() {
  if (otaStarted) return;
  ArduinoOTA.setHostname("esp32-car-ai-thinker-2wd");
  ArduinoOTA.setPassword(otaKey.c_str());
  ArduinoOTA.onStart([]() { motorsStop(); });
  ArduinoOTA.begin();
  otaStarted = true;
}

void startRecoveryAp();

bool connectWifiAndStart() {
  loadWifiCredentials();
  if (wifiSsid.isEmpty()) {
    Serial.println("[WIFI] no credentials; starting recovery AP");
    startRecoveryAp();
    return false;
  }

  motorsStop();
  recoveryApActive = false;
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());

  for (int i = 0; i < 24 && WiFi.status() != WL_CONNECTED; i++) delay(250);
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WIFI] station connection failed; starting recovery AP");
    WiFi.disconnect(true, false);
    startRecoveryAp();
    return false;
  }

  wifiOnline = true;
  recoveryApActive = false;
  startServers();
  startOta();
  Serial.printf("[WIFI] %s @ %s\n", WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
  return true;
}

void startRecoveryAp() {
  motorsStop();
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(UPDATE_AP_SSID, UPDATE_AP_PASSWORD);
  recoveryApActive = true;
  wifiOnline = true;
  startServers();
  startOta();
}

// ===== Bluetooth SPP =====
void onBluetoothEvent(esp_spp_cb_event_t event, esp_spp_cb_param_t* param) {
  if (event == ESP_SPP_SRV_OPEN_EVT) {
    sppClientConnected = true;
  } else if (event == ESP_SPP_CLOSE_EVT) {
    sppClientConnected = false;
    btEmergencyStopRequested = true;
  }
}

void processBluetoothCommand(String cmd) {
  cmd.trim();
  if (cmd.isEmpty()) return;

  if (cmd == "STATUS") {
    SerialBT.println(statusJson(true));
    return;
  }
  if (cmd == "PING") {
    SerialBT.println("PONG:" + String(millis()));
    return;
  }
  if (cmd.startsWith("W:")) {
    String rest = cmd.substring(2);
    int comma = rest.indexOf(',');
    if (comma < 0) {
      SerialBT.println("ERR:FORMAT");
      return;
    }
    String newSsid = rest.substring(0, comma);
    String newPass = rest.substring(comma + 1);
    if (newSsid.isEmpty()) {
      SerialBT.println("ERR:EMPTY_SSID");
      return;
    }
    saveWifiCredentials(newSsid, newPass);
    SerialBT.println("OK:WIFI_SAVED:" + newSsid);
    return;
  }
  if (cmd == "X") {
    bool ok = connectWifiAndStart();
    SerialBT.println(ok ? "OK:WIFI_CONNECTED:" + WiFi.localIP().toString() : "OK:RECOVERY_AP:192.168.4.1");
    return;
  }
  if (cmd == "U") {
    startRecoveryAp();
    SerialBT.println("OK:OTA_AP:192.168.4.1");
    return;
  }
  if (cmd == "REBOOT") {
    motorsStop();
    SerialBT.println("OK:REBOOTING");
    pendingRestart = true;
    restartAtMs = millis() + 400;
    return;
  }
  if (cmd.startsWith("V")) {
    currentSpeed = constrain(cmd.substring(1).toInt(), 50, 255);
    return;
  }
  if (cmd.startsWith("T")) {
    currentTrim = constrain(cmd.substring(1).toInt(), -50, 50);
    return;
  }
  if (cmd.startsWith("H")) {
    ledcWrite(FLASH_LED_PIN, constrain(cmd.substring(1).toInt(), 0, 255));
    return;
  }
  if (cmd.startsWith("M:")) {
    String rest = cmd.substring(2);
    int comma = rest.indexOf(',');
    if (comma < 0) return;
    motorsSet(rest.substring(0, comma).toInt(), rest.substring(comma + 1).toInt());
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
  } else if (cmd == "STATUS") {
    Serial.println(statusJson(false));
  } else if (cmd == "STOP") {
    motorsStop();
  }
}

void setup() {
  Serial.begin(115200);
  delay(150);
  loadMotorConfig();
  setupMotorPwm();
  motorsStop();
  otaKey = loadOrCreateOtaKey();

  SerialBT.register_callback(onBluetoothEvent);
  bool btReady = SerialBT.begin("ESP32_CAM_RC");
  Serial.printf("\nESP32 Car FW %s\n", FW_VERSION);
  Serial.printf("Board: AI Thinker ESP32-CAM\nProfile: %s\n", HARDWARE_PROFILE);
  Serial.printf("Protocol: %d\n", PROTOCOL_VERSION);
  Serial.println("Pins: L=13/12 R=14/15 flash=4");
  Serial.printf("Deadman: %u ms\n", DRIVE_DEADMAN_MS);
  Serial.printf("Bluetooth: %s\n", btReady ? "ready" : "FAILED");

  loadWifiCredentials();
  if (!wifiSsid.isEmpty()) connectWifiAndStart();
}

void loop() {
  if (btEmergencyStopRequested) {
    btEmergencyStopRequested = false;
    motorsStop();
  }

  if (pendingRestart && (int32_t)(millis() - restartAtMs) >= 0) {
    motorsStop();
    delay(80);
    ESP.restart();
  }

  if (driveActive && millis() - lastDriveCommandMs > DRIVE_DEADMAN_MS) {
    deadmanTrips++;
    motorsStop();
  }

  if (otaStarted) ArduinoOTA.handle();

  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\n') {
      processBluetoothCommand(btBuffer);
      btBuffer = "";
    } else if (c != '\r') {
      btBuffer += c;
      if (btBuffer.length() > 160) btBuffer = "";
    }
  }

  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      handleSerialCommand(serialBuffer);
      serialBuffer = "";
    } else if (c != '\r') {
      serialBuffer += c;
      if (serialBuffer.length() > 160) serialBuffer = "";
    }
  }

  delay(5);
}
