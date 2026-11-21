/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.carlauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.InputDevice;
import android.widget.TextView;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RPI5_CAR: shows system health on the home screen: wall clock (updated every second), memory
 * use, and connected peripherals (displays, USB devices and external input devices).
 *
 * <p>Call {@link #start()} when the home screen becomes visible and {@link #stop()} when it is
 * hidden; the panel is refreshed once per second, aligned to the start of each second.
 */
final class SystemHealthMonitor {
    private static final long MB = 1024L * 1024L;
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss", Locale.ROOT);

    private final TextView mTextView;
    private final ActivityManager mActivityManager;
    private final DisplayManager mDisplayManager;
    private final InputManager mInputManager;
    private final UsbManager mUsbManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ActivityManager.MemoryInfo mMemoryInfo = new ActivityManager.MemoryInfo();
    private boolean mRunning;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            mTextView.setText(buildReport());
            // Re-arm at the next whole second so the seconds digit ticks on time.
            long delay = REFRESH_INTERVAL_MS - (System.currentTimeMillis() % REFRESH_INTERVAL_MS);
            mHandler.postAtTime(this, SystemClock.uptimeMillis() + delay);
        }
    };

    SystemHealthMonitor(Context context, TextView textView) {
        mTextView = textView;
        mActivityManager = context.getSystemService(ActivityManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mInputManager = context.getSystemService(InputManager.class);
        // Null on devices without the USB service.
        mUsbManager = context.getSystemService(UsbManager.class);
    }

    void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mRefresh.run();
    }

    void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mRefresh);
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTEM HEALTH\n");
        appendRow(sb, "Clock", LocalDateTime.now().format(CLOCK_FORMAT));
        appendRow(sb, "Memory", memorySummary());
        appendRow(sb, "Displays", displaySummary());
        appendRow(sb, "USB", usbSummary());
        appendRow(sb, "Input", inputSummary());
        // Drop the trailing newline.
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String label, String value) {
        sb.append(String.format(Locale.ROOT, "%-9s %s%n", label, value));
    }

    private String memorySummary() {
        mActivityManager.getMemoryInfo(mMemoryInfo);
        long total = mMemoryInfo.totalMem / MB;
        long avail = mMemoryInfo.availMem / MB;
        long used = total - avail;
        int percent = total > 0 ? (int) (used * 100 / total) : 0;
        return String.format(Locale.ROOT, "%d / %d MB used (%d%%)%s", used, total, percent,
                mMemoryInfo.lowMemory ? "  LOW" : "");
    }

    private String displaySummary() {
        List<String> names = new ArrayList<>();
        for (Display display : mDisplayManager.getDisplays()) {
            names.add(display.getName());
        }
        return names.isEmpty() ? "none" : TextUtils.join(", ", names);
    }

    private String usbSummary() {
        if (mUsbManager == null) {
            return "not supported";
        }
        List<String> names = new ArrayList<>();
        for (UsbDevice device : mUsbManager.getDeviceList().values()) {
            String name = device.getProductName();
            names.add(!TextUtils.isEmpty(name) ? name : String.format(Locale.ROOT, "%04x:%04x",
                    device.getVendorId(), device.getProductId()));
        }
        return names.isEmpty() ? "none" : TextUtils.join(", ", names);
    }

    private String inputSummary() {
        List<String> external = new ArrayList<>();
        int builtIn = 0;
        for (int id : mInputManager.getInputDeviceIds()) {
            InputDevice device = mInputManager.getInputDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            if (device.isExternal()) {
                external.add(device.getName());
            } else {
                builtIn++;
            }
        }
        String externalText = external.isEmpty() ? "no external" : TextUtils.join(", ", external);
        return externalText + " (" + builtIn + " built-in)";
    }
}
