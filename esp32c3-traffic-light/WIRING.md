# ESP32-C3 红黄绿灯接线图

本接线图对应当前固件中的默认配置：

```cpp
constexpr uint8_t RED_PIN = 0;
constexpr uint8_t YELLOW_PIN = 2;
constexpr uint8_t GREEN_PIN = 1;
#define LED3 3
#define LED4 4
#define BUTTON_PIN 21
constexpr bool ACTIVE_HIGH = true;
```

## 方案一：五只独立 LED

每只 LED 都必须串联一个 `220Ω–330Ω` 限流电阻，推荐使用 `330Ω`。

```text
                    330Ω         红色 LED
ESP32-C3 GPIO0 ───/\/\/\/───────|>|──────── GND
                                  A  K

                    330Ω         绿色 LED
ESP32-C3 GPIO1 ───/\/\/\/───────|>|──────── GND
                                  A  K

                    330Ω         黄色 LED
ESP32-C3 GPIO2 ───/\/\/\/───────|>|──────── GND
                                  A  K

                    330Ω       LED3 红色 LED
ESP32-C3 GPIO3 ───/\/\/\/───────|>|──────── GND
                                  A  K

                    330Ω       LED4 绿色 LED
ESP32-C3 GPIO4 ───/\/\/\/───────|>|──────── GND
                                  A  K

ESP32-C3 GPIO21 ─────────────── 按钮 ───────── GND
```

其中：

- `A` 是阳极，也就是 LED 较长的引脚。
- `K` 是阴极，也就是 LED 较短的引脚。
- LED 外壳平边通常对应阴极 `K`。
- 五只 LED 的阴极都连接 ESP32-C3 的 `GND`。
- 不要将 LED 直接连接 GPIO，必须串联限流电阻。

### 接线表

| ESP32-C3 | 限流电阻 | LED | LED 另一端 |
| --- | --- | --- | --- |
| GPIO0 | 330Ω | 红色 LED 阳极 | 阴极接 GND |
| GPIO1 | 330Ω | 绿色 LED 阳极 | 阴极接 GND |
| GPIO2 | 330Ω | 黄色 LED 阳极 | 阴极接 GND |
| GPIO3 | 330Ω | LED3 红色 LED 阳极 | 阴极接 GND；主绿灯时亮 |
| GPIO4 | 330Ω | LED4 绿色 LED 阳极 | 阴极接 GND；主红灯时亮 |
| GPIO21 | 无 | 按钮一端 | 另一端接 GND；按下进入 LED3+LED4 本地显示 |

GPIO21 使用 `INPUT_PULLUP`，因此按钮直接接 GPIO21 与 GND 即可，不需要额外上拉电阻。按住按钮时暂停应用 BLE 灯态，主红黄绿熄灭，LED3 和 LED4 同时常亮；松开后恢复此前灯态并继续处理 BLE 指令。

## 方案二：四针共阴极红黄绿模块

如果模块有 `R、Y、G、GND` 四个引脚：

```text
ESP32-C3 GPIO0 ─── 330Ω ─── R
ESP32-C3 GPIO1 ─── 330Ω ─── G
ESP32-C3 GPIO2 ─── 330Ω ─── Y
ESP32-C3 GND   ───────────── GND / -
```

固件保持：

```cpp
constexpr bool ACTIVE_HIGH = true;
```

有些模块已经自带限流电阻。如果无法确认，仍建议先外接 `330Ω` 电阻测试。

## 方案三：四针共阳极模块

如果模块有 `R、Y、G、VCC/+` 四个引脚，并明确标注为共阳极：

```text
ESP32-C3 GPIO0 ─── 330Ω ─── R
ESP32-C3 GPIO1 ─── 330Ω ─── G
ESP32-C3 GPIO2 ─── 330Ω ─── Y
ESP32-C3 3V3   ───────────── VCC / +
```

同时把固件改为：

```cpp
constexpr bool ACTIVE_HIGH = false;
```

共阳极模块不能把公共端接 `5V` 后再由 ESP32-C3 GPIO 直接控制。ESP32-C3 GPIO
不是 5V 耐压引脚。

## 完整连接示意

```text
                    ┌──────────────────────────────┐
                    │          ESP32-C3            │
                    │                              │
红灯控制  ◄─────────┤ GPIO0                    3V3 ├──── 可选模块电源
绿灯控制  ◄─────────┤ GPIO1                        │
黄灯控制  ◄─────────┤ GPIO2                    GND ├──── 公共地
对向红灯  ◄─────────┤ GPIO3                        │
对向绿灯  ◄─────────┤ GPIO4                        │
模式按钮  ──────────►│ GPIO21                       │
                    └──────────────────────────────┘
       │                 │                 │
      330Ω              330Ω              330Ω
       │                 │                 │
    红色 LED          黄色 LED          绿色 LED
       │                 │                 │
       └─────────────────┴─────────────────┘
                         │
                        GND
```

## 上电前检查

1. 确认主红、主绿、主黄分别连接 GPIO0、GPIO1、GPIO2。
2. 确认 LED3 红灯接 GPIO3、LED4 绿灯接 GPIO4，模式按钮接在 GPIO21 与 GND 之间。
3. 确认每一路 LED 都有限流电阻。
4. 确认 LED 极性没有接反。
5. 确认所有地线连接到同一个 `GND`。
6. 先通过 Android App 的“红、黄、绿、全灭”按钮逐个测试。
7. 主红灯与 LED4 绿灯同时亮、主绿灯与 LED3 红灯同时亮，属于对向灯的正常状态。

## 大功率或 5V/12V 信号灯

GPIO 只能直接驱动小电流 LED。以下设备不能按上图直接连接：

- 5V 或 12V 信号灯模块
- LED 灯带
- 大功率 LED
- 继电器线圈

这类设备需要 MOSFET 或三极管驱动、电源保护和独立电源，并与 ESP32-C3 共地。
