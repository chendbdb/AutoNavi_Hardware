# 高德巡航信号灯 → BLE → ESP32-C3 / ESP32-S3

高德车机版 9.1 在应用进程内接收信号灯广播，并通过 BLE 把灯态发送给 ESP32。
ESP32-C3 驱动外接三色灯，ESP32-S3 使用开发板自带 RGB 灯。

* 高德的安装包在发布内


## 子工程

| 目录 | 用途 |
| --- | --- |
| `amap-auto-9.1-minimal-integrated-ble/` | 最终 Android 工程与可安装 APK |
| `esp32c3-traffic-light/` | Arduino 固件：BLE 外设 + 三色 LED 驱动 |
| `esp32s3-traffic-light/` | Arduino 固件：BLE 外设 + 板载 RGB 灯 |
| `amap-auto-cruise-wrapper-20260523.apk` | 构建 Android 集成版所需的 9.1 底包 |

## 数据链路

1. 高德车机版补丁发送 `AUTONAVI_STANDARD_BROADCAST_SEND`。
2. 高德进程内的 BLE 运行时仅处理 `KEY_TYPE=0xEAA9`，优先解析 `lightsData`。
3. 运行时选择广播中的第一盏灯，将状态转换为
   `TL1,<序号>,<R|Y|G|O>,<倒计时>\n`。
4. ESP32-C3 通过 BLE 接收指令，并保证任意时刻最多只亮一个 LED。
5. 高德发送清除信号、BLE 断开或数据超时时，三个 LED 全灭。

## 快速部署

ESP32-C3 外接三灯：

```text
用 Arduino IDE 打开 esp32c3-traffic-light/esp32c3-traffic-light.ino
选择实际 ESP32-C3 开发板和串口
按接线修改文件顶部 RED_PIN / YELLOW_PIN / GREEN_PIN
上传
```

ESP32-S3 板载 RGB：

```text
用 Arduino IDE 打开 esp32s3-traffic-light/esp32s3-traffic-light.ino
选择与硬件一致的 ESP32-S3 板型
上传；不需要外接 LED
```

构建并安装集成版高德：

```powershell
cd amap-auto-9.1-minimal-integrated-ble
.\build.ps1
adb install -r .\dist\AMapAuto_9.1.0.600087_minimal_integrated_ble.apk
```

启动高德后，下拉通知栏并点击“信号灯蓝牙设置”，打开“启用蓝牙转发”。
可先用红、黄、绿、关按钮验证硬件，再开始高德巡航。

修改和重签第三方 APK 可能触发签名校验，也可能受软件许可或当地法规限制；
请只处理你有权修改的安装包，并先备份。

## 默认接线

默认按“GPIO 输出高电平点亮”配置，每个 LED 必须串联 220–330Ω 电阻：

| ESP32-C3 | 器件 |
| --- | --- |
| GPIO 0 | 红灯正极 |
| GPIO 1 | 绿灯正极 |
| GPIO 2 | 黄灯正极 |
| GND | 三个 LED 负极 |

如果是共阳极模块，把固件中的 `ACTIVE_HIGH` 改成 `false`。不要让裸 LED 不经限流电阻直连。
