package dev.ak.toolbox.hook;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import dev.ak.toolbox.BuildConfig;
import dev.ak.toolbox.Options;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tracks only the tested payment pages. It never reads a code string or bitmap. */
final class PaymentPageHooks {
    private static final String WX = "com.tencent.mm", WX_HOST = "com.tencent.mm.framework.app.UIPageFragmentActivity";
    private static final String ALI = "com.eg.android.AlipayGphone", ALI_PAGE = "com.alipay.mobile.onsitepay.merge.OnsitepayActivity";
    private static final String BASE = "com.tencent.kinda.framework.widget.base.BaseKindaCodeView";
    private static final String WX_QR = "com.tencent.kinda.framework.widget.base.KindaQRCodeViewImpl";
    private static final String WX_BAR = "com.tencent.kinda.framework.widget.base.KindaBarCodeViewImpl";
    private static final String ACTION = "dev.ak.toolbox.payment.PAGE";
    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Activity, WechatState> WECHAT = new WeakHashMap<>();
    private static final Map<Activity, AlipayState> ALIPAY = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EVENTS = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AK-payment-event"); thread.setDaemon(true); return thread;
    });
    private PaymentPageHooks() { }

    static void install(XC_LoadPackage.LoadPackageParam param) {
        if (!param.packageName.equals(param.processName)) return;
        if (WX.equals(param.packageName)) installWechat();
        else if (ALI.equals(param.packageName)) installAlipay();
    }

    private static boolean version(Activity activity, long expected) {
        try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode() == expected; }
        catch (Throwable ignored) { return false; }
    }

    private static void installAlipay() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume", Activity.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                if (ALI_PAGE.equals(activity.getClass().getName()) && version(activity, 212363L)
                        && Options.enabled(activity, Options.REAR_PAYMENT))
                    ALIPAY.computeIfAbsent(activity, AlipayState::new).resume();
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnPause", Activity.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                if (!ALI_PAGE.equals(activity.getClass().getName())) return;
                AlipayState state = ALIPAY.get(activity);
                if (state != null) state.pause();
            }
        });
    }

    private static void installWechat() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate", Activity.class, Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                if (!WX_HOST.equals(activity.getClass().getName()) || !version(activity, 3200L)) return;
                try { installCodeViews(activity.getClassLoader()); }
                catch (Throwable error) { HookSupport.error("微信付款组件未适配", error); }
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume", Activity.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                if (!WX_HOST.equals(activity.getClass().getName()) || !version(activity, 3200L)) return;
                WechatState state = WECHAT.computeIfAbsent(activity, WechatState::new);
                state.resumed = true;
                state.check();
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnPause", Activity.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                if (!WX_HOST.equals(activity.getClass().getName())) return;
                WechatState state = WECHAT.get(activity);
                if (state != null) state.pause(); else send(activity, "stop");
            }
        });
    }

    private static synchronized void installCodeViews(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClass(BASE, loader);
        if (!INSTALLED.add(type)) return;
        XposedBridge.hookAllMethods(type, "createView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.getResult() instanceof View view)) return;
                String name = p.thisObject.getClass().getName();
                if (!WX_QR.equals(name) && !WX_BAR.equals(name)) return;
                Activity activity = activity(view.getContext());
                if (activity == null || !WX_HOST.equals(activity.getClass().getName())) return;
                WechatState state = WECHAT.computeIfAbsent(activity, WechatState::new);
                if (WX_QR.equals(name)) state.qr = new WeakReference<>(view);
                else state.bar = new WeakReference<>(view);
            }
        });
    }

    private static Activity activity(Context context) {
        for (int i = 0; i < 10 && context != null; i++) {
            if (context instanceof Activity activity) return activity;
            if (!(context instanceof ContextWrapper wrapper) || wrapper.getBaseContext() == context) return null;
            context = wrapper.getBaseContext();
        }
        return null;
    }

    private static void send(Activity activity, String command) {
        Context app = activity.getApplicationContext();
        if (app == null) return;
        long eventAt = SystemClock.elapsedRealtimeNanos();
        if (BuildConfig.DEBUG) Log.i("AKPaymentProbe", "send queued=" + command);
        EVENTS.execute(() -> {
            try {
                Intent intent = new Intent(ACTION).setClassName("dev.ak.toolbox", "dev.ak.toolbox.payment.PaymentPageReceiver")
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("command", command).putExtra("eventAt", eventAt);
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                if (BuildConfig.DEBUG) Log.i("AKPaymentProbe", "send beginning=" + command);
                app.sendBroadcast(intent, null, options);
                if (BuildConfig.DEBUG) Log.i("AKPaymentProbe", "send returned=" + command);
            }
            catch (Throwable ignored) { /* The toolbox may be disabled or unavailable. */ }
        });
    }

    private static final class AlipayState {
        final WeakReference<Activity> owner;
        boolean active, suspended;
        final Runnable finishPause = this::finishPause;
        AlipayState(Activity activity) { owner = new WeakReference<>(activity); }
        void resume() {
            MAIN.removeCallbacks(finishPause);
            Activity activity = owner.get();
            if (activity != null) { active = true; suspended = false; send(activity, "start"); }
        }
        void pause() {
            Activity activity = owner.get();
            if (activity == null || !active) return;
            suspended = true;
            send(activity, "suspend");
            MAIN.removeCallbacks(finishPause);
            MAIN.postDelayed(finishPause, 2500);
        }
        void finishPause() {
            Activity activity = owner.get();
            if (suspended && active && activity != null) send(activity, "stop");
            if (suspended) { active = false; suspended = false; }
        }
    }

    private static final class WechatState {
        final WeakReference<Activity> owner;
        WeakReference<View> qr = new WeakReference<>(null), bar = new WeakReference<>(null);
        boolean resumed, active, suspended;
        int reportedState = -1;
        final Runnable tick = this::check;
        final Runnable finishPause = this::finishPause;
        void finishPause() {
            Activity activity = owner.get();
            if (suspended && active && activity != null) send(activity, "stop");
            if (suspended) { active = false; suspended = false; }
        }
        WechatState(Activity activity) { owner = new WeakReference<>(activity); }
        void check() {
            MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (!resumed || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            View qrView = qr.get(), barView = bar.get();
            boolean enabled = Options.enabled(activity, Options.REAR_PAYMENT);
            boolean qrVisible = visible(qrView), barVisible = visible(barView);
            boolean inTop = qrVisible && barVisible && inTopFragment(activity, qrView, barView);
            boolean visible = enabled && inTop;
            int reportState = (enabled ? 1 : 0) | (qrVisible ? 2 : 0) | (barVisible ? 4 : 0) | (inTop ? 8 : 0);
            if (BuildConfig.DEBUG && reportedState != reportState && qrView != null && barView != null) {
                reportedState = reportState;
                Log.i("AKPaymentProbe", "wechat enabled=" + enabled + " qrVisible=" + qrVisible
                    + " barVisible=" + barVisible + " top=" + inTop);
            }
            if (visible && (!active || suspended)) {
                MAIN.removeCallbacks(finishPause);
                active = true; suspended = false; send(activity, "start");
            } else if (!visible && active && !suspended) {
                active = false; send(activity, "stop");
            }
            MAIN.postDelayed(tick, 250);
        }
        void pause() {
            resumed = false; MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (activity != null && active) {
                suspended = true;
                send(activity, "suspend");
                MAIN.removeCallbacks(finishPause);
                MAIN.postDelayed(finishPause, 2500);
            }
        }
        private static boolean visible(View view) {
            if (view == null || !view.isAttachedToWindow() || !view.isShown()) return false;
            for (View current = view; current != null;) {
                if (current.getVisibility() != View.VISIBLE || current.getAlpha() < 0.99f) return false;
                current = current.getParent() instanceof View parent ? parent : null;
            }
            Rect rect = new Rect();
            return view.getGlobalVisibleRect(rect) && rect.width() >= 80 && rect.height() >= 40;
        }
        private static boolean inTopFragment(Activity activity, View first, View second) {
            try {
                Object manager = XposedHelpers.callMethod(activity, "getSupportFragmentManager");
                Object raw = XposedHelpers.callMethod(manager, "getFragments");
                if (!(raw instanceof Iterable<?> fragments)) return false;
                View lastVisibleRoot = null;
                for (Object fragment : fragments) {
                    if (fragment == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isResumed"))
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isVisible"))) continue;
                    Object root = XposedHelpers.callMethod(fragment, "getView");
                    if (root instanceof View view) lastVisibleRoot = view;
                }
                return lastVisibleRoot != null && descendsFrom(first, lastVisibleRoot) && descendsFrom(second, lastVisibleRoot);
            } catch (Throwable ignored) { return false; }
        }
        private static boolean descendsFrom(View view, View root) {
            for (View current = view; current != null;) {
                if (current == root) return true;
                current = current.getParent() instanceof View parent ? parent : null;
            }
            return false;
        }
    }
}
