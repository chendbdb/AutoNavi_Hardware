package amap.minimal.ble;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class BleRuntime implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "AmapMinimalBle";
    private static final String PREFS = "amap_minimal_ble";
    private static final String KEY_ENABLED = "enabled";
    private static final String ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_TRAFFIC_LIGHT = 0xEAA9;
    private static final int KEY_TYPE_LANE_INFO = 13012;
    private static final String LANE_TAG = "AmapLaneData";
    private static final String ROAD_STATE_TAG = "AmapRoadState";
    private static final UUID SERVICE_UUID = UUID.fromString("7d9b0001-9f30-4b51-9d4f-7a1b3e1a0001");
    private static final UUID COMMAND_UUID = UUID.fromString("7d9b0002-9f30-4b51-9d4f-7a1b3e1a0001");
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static BleRuntime instance;

    private final Application app;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong sequence = new AtomicLong();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private boolean scanning;
    private boolean writing;
    private byte[] pending;
    private String lastLanePayload;
    private String latestLaneSummary = "暂无车道数据";
    private Light latestLight;
    private String state = "蓝牙转发已关闭";
    private TextView liveStatus;

    public static synchronized void init(Application app) {
        if (instance != null || app == null) return;
        instance = new BleRuntime(app);
    }

    private BleRuntime(Application application) {
        app = application;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? BluetoothAdapter.getDefaultAdapter() : manager.getAdapter();
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);
        app.registerActivityLifecycleCallbacks(this);
        postSettingsNotification();
        if (isEnabled()) ensureConnection();
        Log.i(TAG, "minimal BLE runtime installed; enabled=" + isEnabled());
    }

    private boolean isEnabled() { return prefs.getBoolean(KEY_ENABLED, false); }

    public static void openSettings(final Activity activity) {
        if (instance == null || activity == null) return;
        instance.showDialog(activity);
    }

    private void postSettingsNotification() {
        try {
            NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            String channelId = "amap_ble_settings";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(channelId, "信号灯蓝牙",
                        NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
            Intent intent = new Intent(app, com.autonavi.amapauto.DiagnoseActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(app, 9101, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, channelId) : new Notification.Builder(app);
            builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("信号灯蓝牙设置")
                    .setContentText(isEnabled() ? "已开启 · 点击设置" : "已关闭 · 点击开启")
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setShowWhen(false);
            manager.notify(9101, builder.build());
        } catch (Throwable t) {
            Log.w(TAG, "settings notification failed", t);
        }
    }

    private void setEnabled(Activity activity, boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            requestPermissions(activity);
            state = "正在连接 ESP32-C3…";
            ensureConnection();
        } else {
            state = "蓝牙转发已关闭";
            disconnect();
        }
        updateStatus();
        postSettingsNotification();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int keyType = intent.getIntExtra("KEY_TYPE", -1);
            if (keyType == KEY_TYPE_LANE_INFO || hasLanePayload(intent)) logLaneData(intent);
            if (keyType != KEY_TYPE_TRAFFIC_LIGHT) return;
            Light light = parse(intent);
            if (light == null) return;
            latestLight = light;
            logCurrentRoadState();
            if (isEnabled()) send(light);
        }
    };

    private boolean hasLanePayload(Intent intent) {
        return !TextUtils.isEmpty(firstStringExtra(intent,
                "EXTRA_DRIVE_WAY", "drive_way_info_json", "driveWayInfo"));
    }

    private void logLaneData(Intent intent) {
        String raw = firstStringExtra(intent,
                "EXTRA_DRIVE_WAY", "drive_way_info_json", "driveWayInfo");
        if (TextUtils.isEmpty(raw)) {
            Bundle extras = intent.getExtras();
            Log.i(LANE_TAG, "车道广播无JSON extras=" + String.valueOf(extras));
            return;
        }
        if (raw.equals(lastLanePayload)) return;
        lastLanePayload = raw;
        Log.d(LANE_TAG, "车道原始JSON=" + raw);

        try {
            JSONObject object = new JSONObject(raw);
            boolean enabled = object.optBoolean("drive_way_enabled", true);
            JSONArray lanes = object.optJSONArray("drive_way_info");
            if (!enabled || lanes == null || lanes.length() == 0) {
                latestLaneSummary = "暂无车道数据";
                Log.i(LANE_TAG, "车道数据已清除 enabled=" + enabled);
                logCurrentRoadState();
                return;
            }

            int declaredSize = object.optInt("drive_way_size", lanes.length());
            StringBuilder summary = new StringBuilder();
            summary.append("车道数=").append(declaredSize).append(" 数据=");
            for (int i = 0; i < lanes.length(); i++) {
                JSONObject lane = lanes.optJSONObject(i);
                if (lane == null) continue;
                int number = lane.optInt("drive_way_number", i);
                int icon = lane.optInt("drive_way_lane_Back_icon",
                        lane.optInt("trafficLaneIcon", lane.optInt("trafficLaneType", -1)));
                if (summary.charAt(summary.length() - 1) != '=') summary.append(" | ");
                summary.append("第").append(i + 1).append("条(number=").append(number).append("):")
                        .append(laneDirection(icon)).append("(icon=").append(icon).append(")");
                if (lane.has("trafficLaneAdvised")) {
                    summary.append(lane.optBoolean("trafficLaneAdvised") ? "[建议]" : "[非建议]");
                }
            }
            latestLaneSummary = summary.toString();
            Log.i(LANE_TAG, latestLaneSummary);
            logCurrentRoadState();
        } catch (Throwable t) {
            Log.w(LANE_TAG, "车道JSON解析失败", t);
        }
    }

    private String firstStringExtra(Intent intent, String... keys) {
        for (String key : keys) {
            String value = intent.getStringExtra(key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private String laneDirection(int icon) {
        switch (icon) {
            case 0: case 15: return "直行";
            case 1: case 16: return "左转";
            case 2: case 17: case 30: case 31: return "左转+直行";
            case 3: case 18: return "右转";
            case 4: case 19: case 32: case 33: return "直行+右转";
            case 5: case 20: return "左掉头";
            case 6: case 21: case 34: case 35: return "左转+右转";
            case 7: case 22: case 36: case 37: case 38: return "左转+直行+右转";
            case 8: case 23: return "右掉头";
            case 9: case 24: case 39: case 40: return "左掉头+直行";
            case 10: case 25: case 41: case 42: return "直行+右掉头";
            case 11: case 26: case 43: case 44: case 48: return "左转+左掉头";
            case 12: case 27: case 45: case 46: return "右转+右掉头";
            case 13: case 14: case 28: case 29: return "直行+扩展车道";
            case 47: return "左转+右掉头+扩展车道";
            case 62: case 85: return "特殊车道";
            case 89: return "无车道线";
            default: return "未知";
        }
    }

    private void logCurrentRoadState() {
        String lightText = latestLight == null
                ? "暂无红绿灯数据"
                : latestLight.colorName() + " " + Math.max(0, latestLight.seconds) + "秒";
        Log.i(ROAD_STATE_TAG, "当前红绿灯=" + lightText + "；" + latestLaneSummary);
    }

    private Light parse(Intent intent) {
        String json = intent.getStringExtra("lightsData");
        if (!TextUtils.isEmpty(json)) {
            try {
                JSONArray list = new JSONArray(json);
                if (list.length() == 0) return new Light('O', 0);
                JSONObject item = list.optJSONObject(0);
                if (item != null) {
                    int status = item.optInt("status", -999);
                    int seconds = item.has("countdown") ? item.optInt("countdown", 0)
                            : item.optInt("countDown", 0);
                    if (status == 0) return new Light('R', seconds);
                    if (status == 1) return new Light('G', seconds);
                    if (status == 2 || status == -1) return new Light('Y', seconds);
                }
            } catch (Throwable t) {
                Log.w(TAG, "lightsData parse failed", t);
            }
        }
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey("trafficLightStatus")) return null;
        int status = intent.getIntExtra("trafficLightStatus", -999);
        int seconds = intent.getIntExtra("redLightCountDownSeconds", 0);
        if (status == 1) return new Light('R', seconds);
        if (status == -1) return new Light('Y', seconds);
        if (status == 4) return new Light('G', seconds);
        return null;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            stopScan();
            connect(result.getDevice());
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            state = "BLE 扫描失败（" + errorCode + "）";
            updateStatus();
            retry();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt target, int status, int newState) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                state = "已连接，正在发现服务…";
                updateStatus();
                try { target.discoverServices(); } catch (Throwable t) { reconnect(); }
            } else {
                state = "ESP32 已断开，等待重连";
                updateStatus();
                reconnect();
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt target, int statusCode) {
            BluetoothGattService service = statusCode == BluetoothGatt.GATT_SUCCESS
                    ? target.getService(SERVICE_UUID) : null;
            characteristic = service == null ? null : service.getCharacteristic(COMMAND_UUID);
            state = characteristic == null ? "未找到 ESP32 信号灯服务" : "ESP32-C3 已连接";
            updateStatus();
            if (characteristic != null) flush(); else reconnect();
        }
        @Override public void onCharacteristicWrite(BluetoothGatt target, BluetoothGattCharacteristic value, int statusCode) {
            writing = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) state = "BLE 写入失败（" + statusCode + "）";
            updateStatus();
            flush();
        }
    };

    private void ensureConnection() {
        if (!isEnabled()) return;
        if (!hasPermissions()) { state = "请授予蓝牙/定位权限"; updateStatus(); return; }
        if (adapter == null) { state = "设备不支持蓝牙"; updateStatus(); return; }
        if (!adapter.isEnabled()) { state = "请先开启系统蓝牙"; updateStatus(); return; }
        if (gatt == null && !scanning) startScan();
    }

    private void startScan() {
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanning = true;
            state = "正在扫描 ESP32 信号灯…";
            updateStatus();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (Throwable t) {
            scanning = false;
            state = "无法启动 BLE 扫描：" + t.getClass().getSimpleName();
            updateStatus();
        }
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
    }

    private void connect(BluetoothDevice device) {
        try {
            if (gatt != null) gatt.close();
            if (Build.VERSION.SDK_INT >= 23) gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else gatt = device.connectGatt(app, false, gattCallback);
        } catch (Throwable t) {
            state = "BLE 连接失败：" + t.getClass().getSimpleName();
            updateStatus();
            retry();
        }
    }

    private void reconnect() {
        BluetoothGatt old = gatt;
        gatt = null;
        characteristic = null;
        try { if (old != null) old.close(); } catch (Throwable ignored) {}
        retry();
    }

    private void retry() {
        handler.removeCallbacks(retryRunnable);
        if (isEnabled()) handler.postDelayed(retryRunnable, 5000L);
    }

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() { ensureConnection(); }
    };

    private void disconnect() {
        handler.removeCallbacks(retryRunnable);
        stopScan();
        BluetoothGatt old = gatt;
        gatt = null;
        characteristic = null;
        pending = null;
        writing = false;
        try { if (old != null) { old.disconnect(); old.close(); } } catch (Throwable ignored) {}
    }

    private void send(Light light) {
        String wire = "TL1," + sequence.incrementAndGet() + "," + light.color + "," + Math.max(0, light.seconds) + "\n";
        pending = wire.getBytes(ASCII);
        state = "高德：" + light.color + " " + light.seconds + "秒";
        updateStatus();
        flush();
    }

    @SuppressWarnings("deprecation")
    private synchronized void flush() {
        if (pending == null || writing || gatt == null || characteristic == null) return;
        byte[] value = pending;
        pending = null;
        try {
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = gatt.writeCharacteristic(characteristic, value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(value);
                started = gatt.writeCharacteristic(characteristic);
            }
            writing = started;
            if (!started) pending = value;
        } catch (Throwable t) {
            pending = value;
            writing = false;
        }
    }

    private boolean usesModernPermissions() {
        return Build.VERSION.SDK_INT >= 31 && app.getApplicationInfo().targetSdkVersion >= 31;
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (usesModernPermissions()) {
            return app.checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED
                    && app.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
        }
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 23 || hasPermissions()) return;
        if (usesModernPermissions()) activity.requestPermissions(new String[]{
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, 9101);
        else activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9101);
    }

    @Override public void onActivityResumed(final Activity activity) {
        String name = activity.getClass().getName();
        if (!"com.autonavi.amapauto.MainMapActivity".equals(name)
                && !"com.autonavi.auto.MainMapActivity".equals(name)) return;
        if (isEnabled()) ensureConnection();
    }

    private void showDialog(final Activity activity) {
        final Dialog dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 22));
        root.setBackground(round(Color.rgb(248, 250, 253), 22, activity));
        TextView title = text(activity, "信号灯蓝牙", 23, Color.rgb(23, 32, 51));
        root.addView(title);
        root.addView(text(activity, "将高德信号灯状态转发到 ESP32-C3", 14, Color.rgb(107, 114, 128)));
        final Switch enabled = new Switch(activity);
        enabled.setText("启用蓝牙转发");
        enabled.setTextSize(17);
        enabled.setTextColor(Color.rgb(23, 32, 51));
        enabled.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        enabled.setChecked(isEnabled());
        root.addView(enabled, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)));
        liveStatus = text(activity, state, 14, Color.rgb(100, 116, 139));
        root.addView(liveStatus);
        TextView testTitle = text(activity, "灯光测试", 16, Color.rgb(23, 32, 51));
        LinearLayout.LayoutParams testTitleLp = new LinearLayout.LayoutParams(-1, -2);
        testTitleLp.topMargin = dp(activity, 16);
        root.addView(testTitle, testTitleLp);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addTest(row, activity, "红", 'R', Color.rgb(239, 68, 68));
        addTest(row, activity, "黄", 'Y', Color.rgb(245, 196, 66));
        addTest(row, activity, "绿", 'G', Color.rgb(16, 185, 129));
        addTest(row, activity, "关", 'O', Color.rgb(15, 75, 60));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(activity, 50));
        rowLp.topMargin = dp(activity, 10);
        root.addView(row, rowLp);
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                setEnabled(activity, checked);
            }
        });
        dialog.setContentView(root);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface value) {
                if ("com.autonavi.amapauto.DiagnoseActivity".equals(activity.getClass().getName())) activity.finish();
            }
        });
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        if (window != null) window.setLayout(Math.min(dp(activity, 560),
                activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32)), -2);
    }

    private void addTest(LinearLayout row, Context context, String label, final char color, int bg) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(round(bg, 12, context));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (isEnabled()) send(new Light(color, 9)); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(context, 3), 0, dp(context, 3), 0);
        row.addView(button, lp);
    }

    private void updateStatus() {
        handler.post(new Runnable() {
            @Override public void run() { if (liveStatus != null) liveStatus.setText(state); }
        });
    }

    private static TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return view;
    }

    private static GradientDrawable round(int color, int radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}

    private static final class Light {
        final char color;
        final int seconds;
        Light(char c, int s) { color = c; seconds = s; }

        String colorName() {
            switch (color) {
                case 'R': return "红灯";
                case 'Y': return "黄灯";
                case 'G': return "绿灯";
                default: return "熄灭";
            }
        }
    }
}
