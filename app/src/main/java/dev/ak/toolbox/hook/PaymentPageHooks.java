package dev.ak.toolbox.hook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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

/** Tracks the tested payment pages and forwards only their visible code views. */
final class PaymentPageHooks {
    private static final String WX = "com.tencent.mm", WX_HOST = "com.tencent.mm.framework.app.UIPageFragmentActivity";
    private static final String WX_APPBRAND = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI";
    private static final String ALI = "com.eg.android.AlipayGphone", ALI_PAGE = "com.alipay.mobile.onsitepay.merge.OnsitepayActivity";
    private static final String BASE = "com.tencent.kinda.framework.widget.base.BaseKindaCodeView";
    private static final String WX_QR = "com.tencent.kinda.framework.widget.base.KindaQRCodeViewImpl";
    private static final String WX_BAR = "com.tencent.kinda.framework.widget.base.KindaBarCodeViewImpl";
    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Activity, WechatState> WECHAT = new WeakHashMap<>();
    private static final Map<Activity, AlipayState> ALIPAY = new WeakHashMap<>();
    private static final Map<Activity, TransitState> TRANSIT = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private PaymentPageHooks() { }

    static void install(XC_LoadPackage.LoadPackageParam param) {
        if (WX.equals(param.packageName)) {
            if (param.packageName.equals(param.processName) || param.processName.contains("appbrand"))
                installWechat();
        } else if (ALI.equals(param.packageName)) {
            if (param.packageName.equals(param.processName) || param.processName.contains("lite"))
                installAlipay();
        }
    }

    private static void installAlipay() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume", Activity.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                String name = activity.getClass().getName();
                if (ALI_PAGE.equals(name) || name.contains("AlipayLogin")) {
                    if (Options.enabled(activity, Options.REAR_PAYMENT) || Options.enabled(activity, Options.REAR_TRANSIT))
                        ALIPAY.computeIfAbsent(activity, AlipayState::new).resume();
                } else if (!isAlipayExcludedPage(name)) {
                    if (Options.enabled(activity, Options.REAR_TRANSIT))
                        TRANSIT.computeIfAbsent(activity, a -> new TransitState(a, false)).resume();
                }
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnPause", Activity.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                String name = activity.getClass().getName();
                if (ALI_PAGE.equals(name) || name.contains("AlipayLogin")) {
                    AlipayState state = ALIPAY.get(activity);
                    if (state != null) state.pause();
                } else if (!isAlipayExcludedPage(name)) {
                    TransitState state = TRANSIT.get(activity);
                    if (state != null) state.pause();
                }
            }
        });
    }

    private static boolean isAlipayExcludedPage(String name) {
        return ALI_PAGE.equals(name) || name.contains("AlipayLogin") || name.contains("Launcher")
            || name.contains("TabLauncher") || name.contains("ScanQRCode")
            || name.contains("Splash") || name.contains("Upgrade") || name.contains("Guide");
    }

    private static void installWechat() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate", Activity.class, Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                if (!WX_HOST.equals(activity.getClass().getName())) return;
                try { installCodeViews(activity.getClassLoader()); }
                catch (Throwable error) { HookSupport.error("微信付款组件未适配", error); }
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume", Activity.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                String name = activity.getClass().getName();
                if (WX_HOST.equals(name)) {
                    WechatState state = WECHAT.computeIfAbsent(activity, WechatState::new);
                    state.resumed = true;
                    state.check();
                } else if (name.contains("AppBrandUI") || name.contains("plugin.appbrand")) {
                    if (Options.enabled(activity, Options.REAR_TRANSIT))
                        TRANSIT.computeIfAbsent(activity, a -> new TransitState(a, true)).resume();
                }
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnPause", Activity.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity activity = (Activity) p.args[0];
                String name = activity.getClass().getName();
                if (WX_HOST.equals(name)) {
                    WechatState state = WECHAT.get(activity);
                    if (state != null) state.pause();
                } else if (name.contains("AppBrandUI") || name.contains("plugin.appbrand")) {
                    TransitState state = TRANSIT.get(activity);
                    if (state != null) state.pause();
                }
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

    private static volatile WeakReference<Activity> CURRENT_ACTIVE = new WeakReference<>(null);

    private static void send(Activity activity, String command) {
        if ("start".equals(command)) {
            CURRENT_ACTIVE = new WeakReference<>(activity);
        } else if ("stop".equals(command)) {
            Activity top = CURRENT_ACTIVE.get();
            if (top == null || top == activity || top.isFinishing() || top.isDestroyed()) {
                CURRENT_ACTIVE.clear();
                RearFrameSender.stop(activity);
            }
        }
    }

    private static final class AlipayState {
        final WeakReference<Activity> owner;
        boolean active, suspended, experimentVisible;
        final Runnable finishPause = this::finishPause;
        final Runnable tick = this::checkExperiment;
        AlipayState(Activity activity) { owner = new WeakReference<>(activity); }
        private int alipayMiss = 0;
        void resume() {
            MAIN.removeCallbacks(finishPause);
            Activity activity = owner.get();
            if (activity != null) { active = true; suspended = false; alipayMiss = 0; send(activity, "start"); checkExperiment(); }
        }
        void pause() {
            MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (activity == null || !active) return;
            suspended = true;
            experimentVisible = false;
            alipayMiss = 0;
            MAIN.removeCallbacks(finishPause);
            MAIN.postDelayed(finishPause, 800);
        }
        void finishPause() {
            Activity activity = owner.get();
            if (suspended && active && activity != null) send(activity, "stop");
            if (suspended) { active = false; suspended = false; }
        }

        private void checkExperiment() {
            MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (!active || suspended || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            View[] views = alipayViews(activity);
            boolean payEnabled = Options.enabled(activity, Options.REAR_PAYMENT);
            boolean transitEnabled = Options.enabled(activity, Options.REAR_TRANSIT);
            boolean hasFocus = activity.hasWindowFocus();
            if (hasFocus && views != null) {
                alipayMiss = 0;
                if (views[1] != null && payEnabled) {
                    experimentVisible = true;
                    RearFrameSender.frame(activity, views[0], views[1]);
                } else if (views[0] != null && views[1] == null && transitEnabled) {
                    experimentVisible = true;
                    RearFrameSender.frameTransit(activity, views[0]);
                } else if (experimentVisible && ++alipayMiss >= 3) {
                    experimentVisible = false;
                    alipayMiss = 0;
                    RearFrameSender.stop(activity);
                }
            } else if (experimentVisible && ++alipayMiss >= 3) {
                experimentVisible = false;
                alipayMiss = 0;
                RearFrameSender.stop(activity);
            }
            MAIN.postDelayed(tick, 250);
        }

        private static View[] alipayViews(Activity activity) {
            try {
                View decor = activity.getWindow().getDecorView();
                java.util.ArrayDeque<View> pending = new java.util.ArrayDeque<>();
                pending.add(decor);
                int scanned = 0;
                View directQr = null, directBar = null;
                while (!pending.isEmpty() && ++scanned <= 2000) {
                    View current = pending.removeFirst();
                    if ("com.alipay.mobile.onsitepay.merge.views.BarCodeView".equals(current.getClass().getName())) {
                        if (XposedHelpers.getBooleanField(current, "isSecurityMask")
                                || XposedHelpers.getBooleanField(current, "isTimeSecurityMask")
                                || XposedHelpers.getBooleanField(current, "networkError")) return null;
                        Object tips = XposedHelpers.getObjectField(current, "isTipsDialogShowing");
                        if (!(tips instanceof java.util.concurrent.atomic.AtomicBoolean showing) || showing.get()) return null;
                        for (String field : new String[] {"limitPayRiskTipsLayout", "riskTipsLayout", "timeRiskTipsLayout"}) {
                            Object risk = XposedHelpers.getObjectField(current, field);
                            if (risk instanceof View view && view.isAttachedToWindow() && view.isShown()) {
                                Rect area = new Rect();
                                if (view.getGlobalVisibleRect(area) && !area.isEmpty()) return null;
                            }
                        }
                        Object qr = XposedHelpers.getObjectField(current, "qrCodeSizeImageView");
                        Object bar = XposedHelpers.getObjectField(current, "barCodeSizeImageView");
                        View q = (qr instanceof View v && WechatState.visible(v)) ? (View) qr : null;
                        View b = (bar instanceof View v && WechatState.visible(v)) ? (View) bar : null;
                        if (q != null) {
                            for (String field : new String[] {"alertInfoLayout", "privacyBannerLayout"}) {
                                Object cover = XposedHelpers.getObjectField(current, field);
                                if (cover instanceof View v && overlaps(v, q, b)) return null;
                            }
                            return new View[] {q, b};
                        }
                        return null;
                    }
                    int id = current.getId();
                    if (id != View.NO_ID && id > 0) {
                        try {
                            String entry = current.getResources().getResourceEntryName(id);
                            if ("qrcode_sizeimageview".equals(entry) && WechatState.visible(current)) {
                                directQr = current;
                            } else if ("barcode_sizeimageview".equals(entry) && WechatState.visible(current)) {
                                directBar = current;
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (current instanceof ViewGroup group)
                        for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
                }
                if (directQr != null) {
                    return new View[] {directQr, directBar};
                }
            } catch (Throwable ignored) { }
            return null;
        }

        private static boolean overlaps(View cover, View first, View second) {
            if (!cover.isAttachedToWindow() || !cover.isShown()) return false;
            Rect area = new Rect(), q = new Rect(), b = new Rect();
            return cover.getGlobalVisibleRect(area)
                && ((first != null && first.getGlobalVisibleRect(q) && Rect.intersects(area, q))
                    || (second != null && second.getGlobalVisibleRect(b) && Rect.intersects(area, b)));
        }
    }

    private static final class TransitState {
        final WeakReference<Activity> owner;
        final boolean isWechat;
        boolean active, suspended, visible;
        final Runnable finishPause = this::finishPause;
        final Runnable tick = this::check;
        TransitState(Activity activity, boolean wechat) {
            owner = new WeakReference<>(activity);
            isWechat = wechat;
        }
        private int missCount = 0;
        void resume() {
            MAIN.removeCallbacks(finishPause);
            Activity activity = owner.get();
            if (activity != null) { active = true; suspended = false; missCount = 0; send(activity, "start"); check(); }
        }
        void pause() {
            MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (activity == null || !active) return;
            suspended = true;
            visible = false;
            missCount = 0;
            send(activity, "suspend");
            MAIN.removeCallbacks(finishPause);
            MAIN.postDelayed(finishPause, 2500);
        }
        void finishPause() {
            Activity activity = owner.get();
            if (suspended && active && activity != null) send(activity, "stop");
            if (suspended) { active = false; suspended = false; }
        }
        private void check() {
            MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (!active || suspended || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            boolean enabled = Options.enabled(activity, Options.REAR_TRANSIT);
            Bitmap captured = enabled ? captureTransitQr(activity) : null;
            if (captured != null) {
                missCount = 0;
                visible = true;
                RearFrameSender.frameTransitBitmap(activity, captured);
            } else if (visible) {
                if (++missCount >= 3) {
                    visible = false;
                    missCount = 0;
                    RearFrameSender.stop(activity);
                }
            }
            MAIN.postDelayed(tick, 300);
        }

        private static Bitmap captureTransitQr(Activity activity) {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return null;
                View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                if (decor == null) return null;
                int w = decor.getWidth(), h = decor.getHeight();
                if (w <= 0 || h <= 0) return null;
                float scale = Math.min(1f, 720f / w);
                int targetW = Math.max(1, Math.round(w * scale)), targetH = Math.max(1, Math.round(h * scale));
                Bitmap snapshot = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(snapshot);
                canvas.drawColor(Color.WHITE);
                canvas.scale(scale, scale);
                decor.draw(canvas);
                int[] pixels = new int[targetW * targetH];
                snapshot.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH);
                dev.ak.toolbox.payment.CodeLocator.Box box = dev.ak.toolbox.payment.CodeLocator.locateQr(targetW, targetH, pixels);
                if (box == null) {
                    snapshot.recycle();
                    return null;
                }
                int bw = box.right() - box.left(), bh = box.bottom() - box.top();
                if (bw < 50 || bh < 50 || bw > targetW * 0.98f) {
                    snapshot.recycle();
                    return null;
                }
                Bitmap qr = Bitmap.createBitmap(snapshot, box.left(), box.top(), bw, bh);
                snapshot.recycle();
                return qr;
            } catch (Throwable error) { return null; }
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
        private int wechatMiss = 0;
        void check() {
            MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (!resumed || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            View qrView = qr.get(), barView = bar.get();
            boolean enabled = Options.enabled(activity, Options.REAR_PAYMENT);
            boolean qrVisible = visible(qrView), barVisible = visible(barView);
            boolean inTop = qrVisible && barVisible && inTopFragment(activity, qrView, barView);
            boolean visible = enabled && inTop && activity.hasWindowFocus();
            if (visible) {
                wechatMiss = 0;
                RearFrameSender.frame(activity, qrView, barView);
            }
            int reportState = (enabled ? 1 : 0) | (qrVisible ? 2 : 0) | (barVisible ? 4 : 0) | (inTop ? 8 : 0);
            if (BuildConfig.DEBUG && reportedState != reportState && qrView != null && barView != null) {
                reportedState = reportState;
                Log.i("AKPaymentProbe", "wechat enabled=" + enabled + " qrVisible=" + qrVisible
                    + " barVisible=" + barVisible + " top=" + inTop);
            }
            if (visible && (!active || suspended)) {
                MAIN.removeCallbacks(finishPause);
                active = true; suspended = false; send(activity, "start");
            } else if (!visible && active && !suspended && ++wechatMiss >= 3) {
                active = false; wechatMiss = 0; send(activity, "stop");
            }
            MAIN.postDelayed(tick, 250);
        }
        void pause() {
            resumed = false; MAIN.removeCallbacks(tick);
            Activity activity = owner.get();
            if (activity != null && active) {
                suspended = true;
                wechatMiss = 0;
                MAIN.removeCallbacks(finishPause);
                MAIN.postDelayed(finishPause, 800);
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
