/* Surface hiding/restoration adapted from HyperCeiler HideStatusBarBeforeScreenshot.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.ak.toolbox.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;
import dev.ak.toolbox.Options;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class StatusBarCapture {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<View> VIEWS = Collections.newSetFromMap(new WeakHashMap<>());
    private record Hidden(int visibility, SurfaceControl surface) {}
    private static final Map<View, Hidden> HIDDEN = new WeakHashMap<>();
    private static final Runnable FAILSAFE = StatusBarCapture::restore;
    private static boolean registered;
    private static int active;
    private StatusBarCapture() {}
    public static void install(ClassLoader loader) {
        Class<?> view = XposedHelpers.findClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", loader);
        XposedBridge.hookAllMethods(view, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View bar = (View) p.thisObject;
                    VIEWS.add(bar);
                    register(bar.getContext().getApplicationContext());
                } catch (Throwable error) { HookSupport.error("状态栏注册", error); }
            }
        });
    }
    private static void register(Context context) {
        if (registered) return;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                try {
                    // Only the real screenshot UID may hide the status bar.
                    int screenshotUid = context.getPackageManager().getPackageUid("com.miui.screenshot", 0);
                    if (getSentFromUid() != screenshotUid) return;
                    long age = SystemClock.uptimeMillis() - intent.getLongExtra("sentAt", 0);
                    if (age < 0 || age > 2000) return;
                    if (intent.getBooleanExtra("finished", true)) {
                        active = Math.max(0, active - 1);
                        if (active == 0) restore();
                    } else if (HookSupport.systemContext != null && Options.enabled(context, Options.HIDE_STATUS)) {
                        active++;
                        hide(intent.getIntExtra("display", 0));
                        MAIN.removeCallbacks(FAILSAFE);
                        MAIN.postDelayed(FAILSAFE, 1800); // One recovery callback, never periodic polling.
                    }
                } catch (Throwable error) { restore(); HookSupport.error("状态栏截屏协调", error); }
            }
        }, new IntentFilter(ScreenshotHooks.CAPTURE), Context.RECEIVER_EXPORTED);
        registered = true;
    }
    private static void hide(int display) {
        for (View view : new ArrayList<>(VIEWS)) {
            if (!view.isAttachedToWindow() || view.getDisplay() == null || view.getDisplay().getDisplayId() != display) continue;
            if (!HIDDEN.containsKey(view)) {
                SurfaceControl surface = surface(view);
                if (!alpha(surface, 0f)) surface = null;
                HIDDEN.put(view, new Hidden(view.getVisibility(), surface));
            }
            view.setVisibility(View.INVISIBLE);
        }
    }
    private static void restore() {
        MAIN.removeCallbacks(FAILSAFE);
        active = 0;
        for (Map.Entry<View, Hidden> entry : new ArrayList<>(HIDDEN.entrySet())) {
            View view = entry.getKey();
            Hidden old = entry.getValue();
            try {
                view.setVisibility(old.visibility());
                if (old.surface() != null) alpha(old.surface().isValid() ? old.surface() : surface(view), 1f);
            } catch (Throwable error) { HookSupport.error("状态栏恢复", error); }
        }
        HIDDEN.clear();
    }
    private static SurfaceControl surface(View view) {
        try {
            Object root = XposedHelpers.callMethod(view, "getViewRootImpl");
            Object result = XposedHelpers.callMethod(root, "getSurfaceControl");
            return result instanceof SurfaceControl control && control.isValid() ? control : null;
        } catch (Throwable ignored) { return null; }
    }
    private static boolean alpha(SurfaceControl surface, float value) {
        if (surface == null || !surface.isValid()) return false;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            CountDownLatch committed = new CountDownLatch(1);
            transaction.setAlpha(surface, value);
            transaction.addTransactionCommittedListener(Runnable::run, committed::countDown);
            transaction.apply();
            // API 37 publicly supports commit callbacks; avoid the blocked apply(boolean) API.
            // Keep the surface recorded even if the bounded wait times out, so restoration is possible.
            try { committed.await(40, TimeUnit.MILLISECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return true;
        } catch (Throwable error) { HookSupport.error("状态栏 Surface", error); return false; }
    }
}
