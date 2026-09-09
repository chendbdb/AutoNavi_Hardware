# ESP32-S3 板载 RGB 信号灯固件

这个版本不外接红、黄、绿 LED，直接使用开发板自带的可寻址 RGB 灯：

- 红灯状态：RGB 显示红色
- 黄灯状态：RGB 同时显示红色和绿色
- 绿灯状态：RGB 显示绿色
- 清除、断开或超时：RGB 熄灭

它与 `esp32c3-traffic-light/` 是两个独立子工程；C3 工程未作修改。

## Arduino IDE 部署

1. 开发板管理器安装最新版 `esp32 by Espressif Systems`，建议 3.x。
2. 打开 `esp32s3-traffic-light.ino`。
3. 选择与你硬件一致的 ESP32-S3 板型。
4. 上传并打开 115200 波特率串口监视器。
5. 启动 Android 桥接 App，使用红、黄、绿测试按钮验证。

Arduino Core 根据所选板型通过 `RGB_BUILTIN` 提供板载灯引脚。代码未找到该宏时
会临时使用 GPIO48；ESP32-S3-DevKitC-1 初版通常使用 GPIO48，v1.1 通常使用
GPIO38。如果串口显示的引脚与板卡原理图不一致，请正确选择板型，或修改文件顶部
的备用 `RGB_BUILTIN` 值。

默认亮度为 `32/255`，可以修改 `SIGNAL_BRIGHTNESS`。板载 RGB 是可寻址 LED，
不能用普通 `digitalWrite()` 分别设置三种颜色。

## BLE 与保护逻辑

- BLE 名称：`AMAP-TRAFFIC-LIGHT-S3`
- 服务和命令 UUID 与 C3 版本一致，Android App 无需改变协议。
- BLE 断开立即熄灭。
- 8 秒没有新灯态立即熄灭。
- 重复或倒退的序号会被丢弃。

如果 C3 和 S3 同时通电，它们会广播同一个服务 UUID，Android App 会连接先扫描到
的一个。联调时建议只给目标板通电。
