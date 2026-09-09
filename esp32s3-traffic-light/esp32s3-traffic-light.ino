#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

// 优先使用所选 Arduino 板型提供的板载 RGB 引脚。
// ESP32-S3-DevKitC-1 初版通常是 GPIO48，v1.1 通常是 GPIO38。
#ifndef RGB_BUILTIN
#define RGB_BUILTIN 48
#warning "Board variant has no RGB_BUILTIN definition; fallback GPIO48 is used"
#endif

constexpr uint8_t RGB_LED_PIN = RGB_BUILTIN;
constexpr uint8_t SIGNAL_BRIGHTNESS = 32;  // 0..255，板载灯不宜过亮
constexpr uint32_t COMMAND_TIMEOUT_MS = 8000;

constexpr char DEVICE_NAME[] = "AMAP-TRAFFIC-LIGHT-S3";
constexpr char SERVICE_UUID[] = "7d9b0001-9f30-4b51-9d4f-7a1b3e1a0001";
constexpr char COMMAND_UUID[] = "7d9b0002-9f30-4b51-9d4f-7a1b3e1a0001";

BLEServer* bleServer = nullptr;
volatile bool clientConnected = false;
volatile bool advertisingRestartPending = false;
uint32_t lastCommandAt = 0;
uint32_t lastSequence = 0;
char currentColor = 'O';

void setLights(char color) {
  uint8_t red = 0;
  uint8_t green = 0;
  uint8_t blue = 0;

  switch (color) {
    case 'R':
      red = SIGNAL_BRIGHTNESS;
      break;
    case 'Y':
      red = SIGNAL_BRIGHTNESS;
      green = SIGNAL_BRIGHTNESS;
      break;
    case 'G':
      green = SIGNAL_BRIGHTNESS;
      break;
    default:
      color = 'O';
      break;
  }

  rgbLedWrite(RGB_LED_PIN, red, green, blue);
  currentColor = color;
  Serial.printf("RGB LED=%c pin=%u rgb=(%u,%u,%u)\n",
                currentColor, RGB_LED_PIN, red, green, blue);
}

bool parseAndApply(const char* command) {
  // 协议：TL1,<sequence>,<R|Y|G|O>,<countdown>\n
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
    Serial.printf("Stale sequence: %lu <= %lu\n", sequence,
                  static_cast<unsigned long>(lastSequence));
    return false;
  }

  lastSequence = static_cast<uint32_t>(sequence);
  lastCommandAt = millis();
  setLights(color);
  Serial.printf("Accepted seq=%lu color=%c countdown=%d\n",
                sequence, color, countdown);
  return true;
}

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer*) {
    clientConnected = true;
    lastSequence = 0;
    lastCommandAt = millis();
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer*) {
    clientConnected = false;
    advertisingRestartPending = true;
    setLights('O');
    Serial.println("BLE client disconnected");
  }
};

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) {
    auto rawValue = characteristic->getValue();
    if (rawValue.length() == 0) return;
    String command(rawValue.c_str());
    command.trim();
    parseAndApply(command.c_str());
  }
};

void setup() {
  Serial.begin(115200);
  delay(200);
  setLights('O');

  BLEDevice::init(DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService* service = bleServer->createService(SERVICE_UUID);
  BLECharacteristic* command = service->createCharacteristic(
      COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE |
      BLECharacteristic::PROPERTY_WRITE_NR);
  command->setCallbacks(new CommandCallbacks());
  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  advertising->start();

  Serial.printf("Advertising as %s; RGB_BUILTIN=%u\n",
                DEVICE_NAME, RGB_LED_PIN);
}

void loop() {
  if (advertisingRestartPending) {
    delay(500);
    bleServer->startAdvertising();
    advertisingRestartPending = false;
    Serial.println("Advertising restarted");
  }
  if (clientConnected && currentColor != 'O'
      && millis() - lastCommandAt > COMMAND_TIMEOUT_MS) {
    Serial.println("Command timeout; RGB LED off");
    setLights('O');
  }
  delay(20);
}
