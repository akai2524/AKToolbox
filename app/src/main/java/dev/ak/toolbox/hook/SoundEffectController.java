/* Audio effect control informed by HyperCeiler's AudioEffectControlForSystem.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.ak.toolbox.hook;

import android.content.Context;
import android.media.audiofx.AudioEffect;
import android.provider.Settings;
import de.robv.android.xposed.XposedHelpers;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** Controls the two native global sound effects used by this MiSound build. */
final class SoundEffectController {
    private static final UUID DOLBY_TYPE = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");
    private static final UUID DOLBY_IMPLEMENTATION = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");
    private final Context context;
    private AudioEffect dolby;
    private AudioEffect miSound;

    SoundEffectController(Context context) { this.context = context; }

    /** Executed on SystemBridge's single worker, not on the SystemUI thread. */
    void setOriginal(boolean original) throws Throwable {
        AudioEffect dolbyEffect = dolby();
        AudioEffect miEffect = miSound();
        if (!dolbyEffect.hasControl() || !miEffect.hasControl())
            throw new IllegalStateException("无法取得音效控制权");

        boolean beforeDolby = readDolby(dolbyEffect);
        boolean beforeDolbyEnabled = dolbyEffect.getEnabled();
        boolean beforeMiSound = miEffect.getEnabled();
        boolean targetDolby = !original;
        try {
            if (beforeMiSound) check(miEffect.setEnabled(false), "关闭小米音效");
            if (beforeDolby != targetDolby || beforeDolbyEnabled != targetDolby)
                writeDolby(dolbyEffect, targetDolby, targetDolby);
            if (readDolby(dolbyEffect) != targetDolby || dolbyEffect.getEnabled() != targetDolby
                || miEffect.getEnabled())
                throw new IllegalStateException("音效状态未按预期生效");
            if (!Settings.Global.putString(context.getContentResolver(), "effect_implementer",
                original ? "none" : "dolby"))
                throw new IllegalStateException("无法同步音效选择");
        } catch (Throwable error) {
            try {
                if (dolbyEffect.hasControl()) writeDolby(dolbyEffect, beforeDolby, beforeDolbyEnabled);
                if (miEffect.hasControl() && miEffect.getEnabled() != beforeMiSound)
                    check(miEffect.setEnabled(beforeMiSound), "恢复小米音效");
            } catch (Throwable rollback) { HookSupport.error("恢复切换前的音效", rollback); }
            throw error;
        }
    }

    private AudioEffect dolby() {
        if (dolby != null && hasControl(dolby)) return dolby;
        release(dolby);
        dolby = (AudioEffect) XposedHelpers.newInstance(AudioEffect.class, DOLBY_TYPE, DOLBY_IMPLEMENTATION, 0, 0);
        return dolby;
    }

    private AudioEffect miSound() {
        if (miSound != null && hasControl(miSound)) return miSound;
        release(miSound);
        Class<?> type = XposedHelpers.findClass("android.media.audiofx.MiSound", ClassLoader.getSystemClassLoader());
        miSound = (AudioEffect) XposedHelpers.newInstance(type, 0, 0);
        return miSound;
    }

    private static boolean hasControl(AudioEffect effect) {
        try { return effect.hasControl(); } catch (RuntimeException ignored) { return false; }
    }

    private static void release(AudioEffect effect) {
        if (effect != null) try { effect.release(); } catch (RuntimeException ignored) { }
    }

    private static boolean readDolby(AudioEffect effect) {
        byte[] payload = new byte[12]; // Parameter 0 is the Dolby enable state.
        Object status = XposedHelpers.callMethod(effect, "getParameter", 5, payload);
        if (!(status instanceof Integer value) || value < 0) throw new IllegalStateException("无法读取杜比音效状态");
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt() > 0;
    }

    private static void writeDolby(AudioEffect effect, boolean parameter, boolean enabled) {
        byte[] payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(1).putInt(parameter ? 1 : 0).array();
        Object status = XposedHelpers.callMethod(effect, "setParameter", 5, payload);
        if (!(status instanceof Integer value)) throw new IllegalStateException("无法设置杜比音效状态");
        check(value, "设置杜比音效");
        check(effect.setEnabled(enabled), "切换杜比音效");
    }

    private static void check(int status, String action) {
        if (status < 0) throw new IllegalStateException(action + "失败：" + status);
    }
}
