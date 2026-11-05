/* Headset route handling informed by HyperCeiler's AutoEffectSwitchForSystem.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.ak.toolbox.hook;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import de.robv.android.xposed.XposedHelpers;
import dev.ak.toolbox.Options;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/** Uses the existing SystemUI process and audio-device notifications; no polling service. */
final class HeadsetAudioSwitch {
    private static final long SETTLE_MS = 1200;
    private static HeadsetAudioSwitch instance;
    private final Context context;
    private final Executor work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final SoundEffectController effects;
    private boolean listening;
    private Boolean lastConnected;
    private long generation;

    private HeadsetAudioSwitch(Context context, Executor work) {
        this.context = context;
        this.work = work;
        this.audio = context.getSystemService(AudioManager.class);
        this.effects = new SoundEffectController(context);
    }

    static void install(Context context, Executor work) throws Exception {
        if (instance != null) return;
        if (context.getPackageManager().getPackageInfo("com.miui.misound", 0).getLongVersionCode() != 260917L)
            throw new IllegalStateException("小米音效版本与适配版本不匹配");
        HeadsetAudioSwitch switcher = new HeadsetAudioSwitch(context, work);
        if (switcher.audio == null) throw new IllegalStateException("音频服务不可用");
        switcher.start();
        instance = switcher;
    }

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { changed(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { changed(); }
    };

    private void start() {
        ContentObserver settings = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { work.execute(HeadsetAudioSwitch.this::updateEnabled); }
        };
        context.getContentResolver().registerContentObserver(
            Settings.Secure.getUriFor(Options.HEADSET_SOUND), false, settings);
        work.execute(this::updateEnabled);
    }

    private void updateEnabled() {
        try {
            boolean enabled = Options.enabled(context, Options.HEADSET_SOUND);
            if (enabled && !listening) {
                lastConnected = null;
                audio.registerAudioDeviceCallback(devices, main);
                listening = true;
                schedule();
            } else if (!enabled && listening) {
                listening = false;
                lastConnected = null;
                generation++;
                audio.unregisterAudioDeviceCallback(devices);
            }
        } catch (Throwable error) { HookSupport.error("耳机音效监听", error); }
    }

    private void changed() { work.execute(this::schedule); }

    private void schedule() {
        if (!listening) return;
        long current = ++generation;
        main.postDelayed(() -> work.execute(() -> {
            if (!listening || current != generation || !Options.enabled(context, Options.HEADSET_SOUND)) return;
            try {
                boolean connected = hasHeadphones();
                if (lastConnected != null && connected == lastConnected) return;
                // When first enabled without a headset, preserve the user's current speaker effect.
                if (lastConnected == null && !connected) {
                    lastConnected = false;
                    HookSupport.log("耳机音效监听已就绪，当前未识别到耳机");
                    return;
                }
                effects.setOriginal(connected);
                lastConnected = connected;
                HookSupport.log(connected ? "耳机音效已切换为原声" : "耳机断开，音效已切换为杜比全景声");
            } catch (Throwable error) { HookSupport.error("耳机音效切换", error); }
        }), SETTLE_MS);
    }

    private boolean hasHeadphones() {
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
            if ((type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                && isBluetoothHeadphone(device)) return true;
        }
        return false;
    }

    @SuppressLint("MissingPermission") // The supported SystemUI has BLUETOOTH_CONNECT.
    private boolean isBluetoothHeadphone(AudioDeviceInfo output) {
        try {
            String address = output.getAddress();
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                HookSupport.log("蓝牙音频设备没有可识别的耳机类型");
                return false;
            }
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            BluetoothDevice device = adapter == null ? null : adapter.getRemoteDevice(address);
            BluetoothClass deviceClass = device == null ? null : device.getBluetoothClass();
            if (deviceClass == null) {
                HookSupport.log("蓝牙音频设备未提供设备类型");
                return identifyUnclassified(device);
            }
            int kind = deviceClass.getDeviceClass();
            boolean headphone = kind == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                || kind == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET;
            if (headphone) return true;
            if (kind == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER
                || kind == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) return false;
            boolean identified = identifyUnclassified(device);
            if (!identified) HookSupport.log("蓝牙音频设备未识别为耳机，类型：" + kind);
            return identified;
        } catch (RuntimeException ignored) { return false; }
    }

    private boolean identifyUnclassified(BluetoothDevice device) {
        try {
            int methodUser = XposedHelpers.getStaticIntField(BluetoothDevice.class, "USER_METHOD");
            int methodDefault = XposedHelpers.getStaticIntField(BluetoothDevice.class, "DEFAULT_METHOD");
            int methodAuto = XposedHelpers.getStaticIntField(BluetoothDevice.class, "AUTO_METHOD");
            int head = XposedHelpers.getStaticIntField(BluetoothDevice.class, "MI_DEVICE_TYPE_HEADSET");
            int speaker = XposedHelpers.getStaticIntField(BluetoothDevice.class, "MI_DEVICE_TYPE_SPEAKER");
            int car = XposedHelpers.getStaticIntField(BluetoothDevice.class, "MI_DEVICE_TYPE_CARKIT");
            int user = (int) XposedHelpers.callMethod(device, "getRemoteDeviceTypeData", methodUser);
            int resolved = (int) XposedHelpers.callMethod(device, "getRemoteDeviceTypeData", methodDefault);
            int automatic = (int) XposedHelpers.callMethod(device, "getRemoteDeviceTypeData", methodAuto);
            HookSupport.log("蓝牙音频类型：用户=" + user + "，系统=" + resolved + "，自动=" + automatic);
            for (int value : new int[]{user, resolved, automatic}) {
                if (value == head) return true;
                if (value == speaker || value == car) return false;
            }
        } catch (Throwable error) { HookSupport.error("读取小米蓝牙设备类型", error); }
        try {
            int key = XposedHelpers.getStaticIntField(BluetoothDevice.class, "METADATA_DEVICE_TYPE");
            Object raw = XposedHelpers.callMethod(device, "getMetadata", key);
            if (!(raw instanceof byte[] bytes) || bytes.length == 0) return false;
            String value = new String(bytes, StandardCharsets.UTF_8);
            String wiredHead = (String) XposedHelpers.getStaticObjectField(BluetoothDevice.class, "DEVICE_TYPE_HEADSET");
            String wirelessHead = (String) XposedHelpers.getStaticObjectField(BluetoothDevice.class, "DEVICE_TYPE_UNTETHERED_HEADSET");
            boolean headset = value.equals(wiredHead) || value.equals(wirelessHead);
            HookSupport.log("蓝牙设备元数据报告为耳机：" + headset);
            return headset;
        } catch (Throwable error) { HookSupport.error("读取蓝牙设备元数据", error); return false; }
    }
}
