/* Screenshot feature gates and capture coordination adapted from HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.akai.toolbox.hook;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import dev.akai.toolbox.Options;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class ScreenshotHooks {
    public static final String CAPTURE = "dev.akai.toolbox.CAPTURE_STATE";
    private static Boolean supportedVersion;
    private ScreenshotHooks() {}
    private static boolean supported(Context context) {
        if (supportedVersion == null) {
            try { supportedVersion = context.getPackageManager().getPackageInfo("com.miui.screenshot", 0)
                .getLongVersionCode() == 10603042L; }
            catch (Throwable error) { supportedVersion = false; }
            if (!supportedVersion) HookSupport.log("截屏版本不匹配，已跳过截屏增强");
        }
        return supportedVersion;
    }
    public static void install(ClassLoader loader) {
        // Util.o(Context) is the clipboard prohibition gate in RELEASE-1.6.3.42.
        XposedHelpers.findAndHookMethod("com.miui.screenshot.util.Util", loader, "o", Context.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Context context = (Context) p.args[0];
                    if (supported(context) && Options.enabled(context, Options.COPY)) p.setResult(false);
                }
            });
        XposedHelpers.findAndHookMethod("com.miui.screenshot.core.util.DisplayCapture", loader,
            "captureDisplay", Context.class, int.class, Rect.class, String[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Context context = (Context) p.args[0];
                    if (!supported(context) || !Options.enabled(context, Options.HIDE_STATUS)) return;
                    try {
                        signal(context, false, (int) p.args[1]);
                        p.setObjectExtra("akai.hidden", true);
                        // Let SystemUI apply the surface transaction before capture begins.
                        SystemClock.sleep(80);
                    } catch (Throwable error) { HookSupport.error("截屏隐藏开始", error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra("akai.hidden"))) return;
                    try { signal((Context) p.args[0], true, (int) p.args[1]); }
                    catch (Throwable error) { HookSupport.error("截屏隐藏恢复", error); }
                }
            });
        HookSupport.log("截屏复制与截屏隐藏已适配");
    }
    private static void signal(Context context, boolean finished, int display) {
        Intent intent = new Intent(CAPTURE).setPackage("com.android.systemui")
            .putExtra("finished", finished).putExtra("display", display)
            .putExtra("sentAt", SystemClock.uptimeMillis()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}

