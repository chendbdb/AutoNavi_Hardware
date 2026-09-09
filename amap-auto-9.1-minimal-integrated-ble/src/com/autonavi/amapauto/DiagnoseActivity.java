package com.autonavi.amapauto;

import android.app.Activity;
import android.os.Bundle;

import amap.minimal.ble.BleRuntime;

/** Existing manifest component repurposed as the in-APK BLE settings screen. */
public final class DiagnoseActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BleRuntime.openSettings(this);
    }
}
