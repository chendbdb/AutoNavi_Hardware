# 高德 9.1 最小 BLE 集成版

本工程以已验证可在小米手机运行的 `amap-auto-cruise-wrapper-20260523.apk` 为底包。

设计原则：

- 使用 apktool 2.9.3 的 `-r` 模式，不重编 `resources.arsc`。
- 保留原 APK 的 `HookApplication`、`b90` 签名绕过和 wrapper 广播补丁。
- 只增加一个进程内 BLE 运行时，不新增 Android Service、Activity 或 Manifest 组件。
- 不在地图 `SurfaceView` 上叠加控件；通知栏常驻“信号灯蓝牙设置”入口，点击后打开同一 APK 内的设置页。
- 新安装默认关闭 BLE；启用后连接 ESP32-C3 并发送 `TL1,序号,颜色,秒数`。

构建：

```powershell
.\build.ps1
```

输出：`dist/AMapAuto_9.1.0.600087_minimal_integrated_ble.apk`

工程内仅保留两个运行时源文件：

- `BleRuntime.java`：广播解析、BLE 连接、通知入口和设置界面。
- `DiagnoseActivity.java`：复用原 APK 已注册的诊断页面作为设置入口。

`tools/apktool_2.9.3.jar` 是构建依赖，因此随工程保留。`work/` 是可随时删除的临时构建目录。

## 查看左转、直行、右转车道数据

运行时会监听高德 `KEY_TYPE=13012` 的车道广播并解析 `EXTRA_DRIVE_WAY`。
车道日志不受“启用蓝牙转发”开关影响，启动高德后执行：

```powershell
adb logcat -s AmapLaneData:I
```

如需同时查看“从左到右的具体车道方向”和“当前红绿灯”，使用：

```powershell
adb logcat -v time -s AmapRoadState:I AmapLaneData:I
```

合并日志示例：

```text
I/AmapRoadState: 当前红绿灯=红灯 75秒；车道数=3 数据=第1条(number=0):左转(icon=16) | 第2条(number=1):直行(icon=15)[建议] | 第3条(number=2):右转(icon=18)
```

车道顺序按照高德 `drive_way_number` 从左到右输出。红绿灯与车道来自两路独立广播；当前版本显示当前路口灯色，不使用实测不可靠的 `dir` 字段强行关联某一条车道。

日志会同时输出原始 JSON 和解析结果，例如：

```text
I/AmapLaneData: 车道数=3 数据=第1条(number=0):左转(icon=16) | 第2条(number=1):直行(icon=15)[建议] | 第3条(number=2):右转(icon=18)
```

相同的车道 JSON 只打印一次，避免高德重复广播造成日志刷屏。方向支持左转、
直行、右转、掉头以及组合车道；未知图标仍会保留原始 `icon` 编号，便于继续适配。

此稳定方案刻意不合并 XML layout，也不向地图 DecorView 添加 View；这两种方式都会影响高德原生地图 Surface。
