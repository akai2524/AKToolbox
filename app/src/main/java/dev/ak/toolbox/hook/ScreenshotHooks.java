/* Screenshot feature gates and capture coordination adapted from HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.ak.toolbox.hook;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import dev.ak.toolbox.Options;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class ScreenshotHooks {
    public static final String CAPTURE = "dev.ak.toolbox.CAPTURE_STATE";
    private ScreenshotHooks() {}
    public static void install(ClassLoader loader) {
        // Util.o(Context) is the clipboard prohibition gate in RELEASE-1.6.3.42 and 1.6.3.44.
        try { XposedHelpers.findAndHookMethod("com.miui.screenshot.util.Util", loader, "o", Context.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Context context = (Context) p.args[0];
                    if (Options.enabled(context, Options.COPY)) p.setResult(false);
                }
            });
            HookSupport.log("截屏复制 Hook 已加载");
        } catch (Throwable error) { HookSupport.error("截屏复制 Hook", error); }
        try { XposedHelpers.findAndHookMethod("com.miui.screenshot.core.util.DisplayCapture", loader,
            "captureDisplay", Context.class, int.class, Rect.class, String[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Context context = (Context) p.args[0];
                    if (!Options.enabled(context, Options.HIDE_STATUS)) return;
                    try {
                        signal(context, false, (int) p.args[1]);
                        p.setObjectExtra("ak.hidden", true);
                        // Let SystemUI apply the surface transaction before capture begins.
                        SystemClock.sleep(80);
                    } catch (Throwable error) { HookSupport.error("截屏隐藏开始", error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra("ak.hidden"))) return;
                    try { signal((Context) p.args[0], true, (int) p.args[1]); }
                    catch (Throwable error) { HookSupport.error("截屏隐藏恢复", error); }
                }
            });
            HookSupport.log("截屏隐藏 Hook 已加载");
        } catch (Throwable error) { HookSupport.error("截屏隐藏 Hook", error); }
    }
    private static void signal(Context context, boolean finished, int display) {
        Intent intent = new Intent(CAPTURE).setPackage("com.android.systemui")
            .putExtra("finished", finished).putExtra("display", display)
            .putExtra("sentAt", SystemClock.uptimeMillis()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}

