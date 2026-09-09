#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <atomic>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

// ---------- ESP32-C3 引脚 ----------
constexpr uint8_t RED_PIN    = 0;
constexpr uint8_t YELLOW_PIN = 2;
constexpr uint8_t GREEN_PIN  = 1;
#define LED3 3       // 主绿灯时点亮的红灯（对向红灯）
#define LED4 4       // 主红灯时点亮的绿灯（对向绿灯）
#define BUTTON_PIN 21 // 按住时暂停 BLE 灯态并点亮 LED3、LED4
constexpr bool ACTIVE_HIGH = true;       // 共阳极改为 false
constexpr uint32_t COMMAND_TIMEOUT_MS = 8000;

// 周期是一次完整的“亮 + 灭”，实际每半个周期切换一次输出。
constexpr uint32_t RED_BLINK_PERIOD_MS = 200;
constexpr uint32_t GREEN_BLINK_PERIOD_MS = 500;
constexpr uint32_t YELLOW_BLINK_PERIOD_MS = 1000;
constexpr uint32_t IDLE_CYCLE_STEP_MS = 500;
constexpr uint32_t BUTTON_DEBOUNCE_MS = 30;
constexpr int BLINK_COUNTDOWN_SECONDS = 3;

constexpr char DEVICE_NAME[] = "AMAP-TRAFFIC-LIGHT";
constexpr char SERVICE_UUID[] = "7d9b0001-9f30-4b51-9d4f-7a1b3e1a0001";
constexpr char COMMAND_UUID[] = "7d9b0002-9f30-4b51-9d4f-7a1b3e1a0001";

BLEServer* bleServer = nullptr;
std::atomic<bool> clientConnected{false};
std::atomic<bool> advertisingRestartPending{false};
std::atomic<bool> buttonOverride{false};
uint32_t lastCommandAt = 0;
uint32_t lastSequence = 0;
char currentColor = 'O';
int currentCountdown = 0;
bool blinkVisible = true;
uint32_t lastBlinkAt = 0;
uint8_t idleStep = 0;
bool idleCycleStarted = false;
uint32_t lastIdleStepAt = 0;
bool lastButtonReading = HIGH;
bool stableButtonState = HIGH;
uint32_t lastButtonChangeAt = 0;

void writeLed(uint8_t pin, bool on) {
  digitalWrite(pin, on == ACTIVE_HIGH ? HIGH : LOW);
}

void showLight(char color, bool visible) {
  writeLed(RED_PIN, false);
  writeLed(YELLOW_PIN, false);
  writeLed(GREEN_PIN, false);
  writeLed(LED3, false);
  writeLed(LED4, false);

  switch (color) {
    case 'R':
      // 主红灯闪烁时，对向绿灯仍保持常亮。
      writeLed(LED4, true);
      if (visible) writeLed(RED_PIN, true);
      break;
    case 'Y':
      if (visible) writeLed(YELLOW_PIN, true);
      break;
    case 'G':
      // 主绿灯闪烁时，对向红灯仍保持常亮。
      writeLed(LED3, true);
      if (visible) writeLed(GREEN_PIN, true);
      break;
    default: break;
  }
}

void showButtonOverride() {
  writeLed(RED_PIN, false);
  writeLed(YELLOW_PIN, false);
  writeLed(GREEN_PIN, false);
  writeLed(LED3, true);
  writeLed(LED4, true);
}

void setSignal(char color, int countdown) {
  if (color != 'R' && color != 'Y' && color != 'G') color = 'O';
  currentColor = color;
  currentCountdown = countdown < 0 ? 0 : countdown;
  blinkVisible = true;
  lastBlinkAt = millis();
  if (!buttonOverride.load()) showLight(currentColor, true);
}

void updateButtonOverride() {
  const bool reading = digitalRead(BUTTON_PIN);
  const uint32_t now = millis();

  if (reading != lastButtonReading) {
    lastButtonReading = reading;
    lastButtonChangeAt = now;
  }

  if (reading == stableButtonState || now - lastButtonChangeAt < BUTTON_DEBOUNCE_MS) return;
  stableButtonState = reading;

  if (stableButtonState == LOW) {
    buttonOverride.store(true);
    showButtonOverride();
    return;
  }

  buttonOverride.store(false);
  blinkVisible = true;
  lastBlinkAt = now;
  lastCommandAt = now;
  showLight(currentColor, true);
}

uint32_t blinkPeriodForCurrentSignal() {
  if (currentColor == 'Y') return YELLOW_BLINK_PERIOD_MS;

  const bool isFinish = currentCountdown < BLINK_COUNTDOWN_SECONDS;
  if (currentColor == 'R' && isFinish) return RED_BLINK_PERIOD_MS;
  if (currentColor == 'G' && isFinish) return GREEN_BLINK_PERIOD_MS;
  return 0;
}

void updateBlink() {
  const uint32_t period = blinkPeriodForCurrentSignal();
  if (period == 0 || currentColor == 'O') {
    if (!blinkVisible) {
      blinkVisible = true;
      showLight(currentColor, true);
    }
    return;
  }

  const uint32_t now = millis();
  if (now - lastBlinkAt >= period / 2) {
    lastBlinkAt = now;
    blinkVisible = !blinkVisible;
    showLight(currentColor, blinkVisible);
  }
}

void updateDisconnectedCycle() {
  if (clientConnected.load()) return;

  const uint32_t now = millis();
  if (idleCycleStarted && now - lastIdleStepAt < IDLE_CYCLE_STEP_MS) return;
  if (clientConnected.load()) return;

  static constexpr char IDLE_COLORS[] = {'R', 'Y', 'G'};
  showLight(IDLE_COLORS[idleStep], true);
  if (clientConnected.load()) {
    showLight('O', false);
    idleCycleStarted = false;
    return;
  }
  idleStep = (idleStep + 1) % 3;
  idleCycleStarted = true;
  lastIdleStepAt = now;
}

bool parseAndApply(const char* command) {
  // 按钮按住时进入本地显示模式，忽略 BLE 指令且不打印错误。
  if (buttonOverride.load()) return true;

  char prefix[4] = {};
  unsigned long sequence = 0;
  char color = '\0';
  int countdown = 0;
  int fields = sscanf(command, "%3[^,],%lu,%c,%d",
                      prefix, &sequence, &color, &countdown);

  if (fields != 4 || strcmp(prefix, "TL1") != 0) {
    Serial.printf("Invalid command: %s\n", command);
    return false;
  }
  if (color != 'R' && color != 'Y' && color != 'G' && color != 'O') {
    Serial.printf("Invalid color: %c\n", color);
    return false;
  }
  if (lastSequence != 0 && sequence <= lastSequence) {
    Serial.printf("Stale sequence: %lu <= %lu\n", sequence, lastSequence);
    return false;
  }

  lastSequence = static_cast<uint32_t>(sequence);
  lastCommandAt = millis();
  setSignal(color, countdown);
  return true;
}

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    clientConnected.store(true);
    lastSequence = 0;
    lastCommandAt = millis();
    idleCycleStarted = false;
    setSignal('O', 0);
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer* server) override {
    clientConnected.store(false);
    advertisingRestartPending.store(true);
    idleStep = 0;
    idleCycleStarted = false;
    setSignal('O', 0);
    Serial.println("BLE client disconnected");
  }
};

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    String val = characteristic->getValue();
    if (val.length() == 0) return;

    // 独立任务解析，避免阻塞 BLE 回调。
    xTaskCreate([](void* arg) {
      String cmd = *(String*)arg;
      delete (String*)arg;
      cmd.trim();
      parseAndApply(cmd.c_str());
      vTaskDelete(nullptr);
    }, "cmdParse", 2048, new String(val), 1, nullptr);
  }
};

void setup() {
  Serial.begin(115200);
  esp_log_level_set("*", ESP_LOG_ERROR);

  pinMode(RED_PIN, OUTPUT);
  pinMode(YELLOW_PIN, OUTPUT);
  pinMode(GREEN_PIN, OUTPUT);
  pinMode(LED3, OUTPUT);
  pinMode(LED4, OUTPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP); // 按下接 GND，松开由内部上拉恢复 HIGH
  setSignal('O', 0);

  BLEDevice::init(DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService* service = bleServer->createService(SERVICE_UUID);
  BLECharacteristic* cmdChar = service->createCharacteristic(
      COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  cmdChar->setCallbacks(new CommandCallbacks());
  service->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);
  adv->setMaxPreferred(0x12);
  adv->start();

  Serial.printf("BLE广播开启 设备名：%s\n", DEVICE_NAME);
  Serial.println("引脚：主红=0 主绿=1 主黄=2 对向红LED3=3 对向绿LED4=4 按钮=21");
}

void loop() {
  updateButtonOverride();
  if (buttonOverride.load()) {
    delay(10);
    return;
  }

  if (advertisingRestartPending.load()) {
    advertisingRestartPending.store(false);
    xTaskCreate([](void*) {
      vTaskDelay(pdMS_TO_TICKS(500));
      bleServer->startAdvertising();
      vTaskDelete(nullptr);
    }, "restartAdv", 1024, nullptr, 1, nullptr);
  }

  if (clientConnected.load() && currentColor != 'O') {
    if (millis() - lastCommandAt > COMMAND_TIMEOUT_MS) {
      Serial.println("指令超时，全部关灯");
      setSignal('O', 0);
    }
  }

  if (clientConnected.load()) updateBlink();
  else updateDisconnectedCycle();

  delay(10);
}
