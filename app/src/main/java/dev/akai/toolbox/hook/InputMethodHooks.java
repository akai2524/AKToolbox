/*
 * Based on HyperCeiler UnlockIme, MiuiImeSupport, MiuiImeInsets and
 * InputMethodClassLoaderDispatcher, commit 284e33db6f57cf7481abc134eaa21c088e205179.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.akai.toolbox.hook;

import android.app.Application;
import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.inputmethodservice.InputMethodService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupWindow;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.akai.toolbox.Options;
import dev.akai.toolbox.ImeListReceiver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Extends the system's existing MIUI keyboard bottom area to scoped third-party IMEs. */
public final class InputMethodHooks {
    private static final String MANAGER = "com.miui.inputmethod.InputMethodBottomManager";
    private static final Set<ClassLoader> LOADERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final KeyboardInsets INSETS = new KeyboardInsets();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REQUEST_TIMEOUT_MS = 1500;
    private static final ThreadLocal<ArrayList<InputMethodInfo>> POPUP_INPUT_METHODS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPENING_POPUP = new ThreadLocal<>();
    private static boolean installed, viewHooksInstalled, switchHookInstalled;
    private static boolean popupPending;
    private static long popupGeneration;
    private InputMethodHooks() { }

    public static void installWhenApplicationStarts(XC_LoadPackage.LoadPackageParam packageInfo) {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.args[0];
                    if (installed || !packageInfo.packageName.equals(app.getPackageName())) return;
                    try {
                        boolean optimize = !isXiaomiCustomized(app.getPackageName())
                            && Options.enabled(app, Options.THIRD_PARTY_IME);
                        boolean showAll = Options.enabled(app, Options.IME_SWITCH_LIST);
                        if ((!optimize && !showAll) || !isInputMethod(app) || !supportsMiuiBottom()) return;
                        install(app, packageInfo.classLoader, optimize, showAll);
                        installed = true;
                    } catch (Throwable error) { HookSupport.error("输入法全面屏优化", error); }
                }
            });
    }

    private static boolean isXiaomiCustomized(String name) {
        return "com.iflytek.inputmethod.miui".equals(name)
            || "com.sohu.inputmethod.sogou.xiaomi".equals(name)
            || "com.baidu.input_mi".equals(name)
            || "com.miui.catcherpatch".equals(name)
            || "com.xiaomi.type".equals(name);
    }

    private static boolean isInputMethod(Application app) {
        Intent intent = new Intent(InputMethod.SERVICE_INTERFACE).setPackage(app.getPackageName());
        int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        for (ResolveInfo service : app.getPackageManager().queryIntentServices(intent, flags)) {
            if (service.serviceInfo != null
                && "android.permission.BIND_INPUT_METHOD".equals(service.serviceInfo.permission)) return true;
        }
        return false;
    }

    private static boolean supportsMiuiBottom() {
        return "1".equals(XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.os.SystemProperties", null),
            "get", "ro.miui.support_miui_ime_bottom", "0"));
    }

    private static void install(Application app, ClassLoader appLoader, boolean optimize, boolean showAll) {
        if (optimize) {
            Class<?> injector = XposedHelpers.findClassIfExists(
                "android.inputmethodservice.InputMethodServiceInjector", appLoader);
            if (injector == null) injector = XposedHelpers.findClassIfExists(
                "android.inputmethodservice.InputMethodServiceStubImpl", appLoader);
            if (injector != null) {
                installSupport(injector);
                installNavigationColor(injector);
            }
        }
        Class<?> loader = XposedHelpers.findClass("android.inputmethodservice.InputMethodModuleManager", null);
        XposedHelpers.findAndHookMethod(loader, "loadDex", ClassLoader.class, String.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !(param.args[0] instanceof ClassLoader pluginLoader)) return;
                try { installPlugin(pluginLoader, app, optimize, showAll); }
                catch (Throwable error) { HookSupport.error("输入法底部栏", error); }
            }
        });
        HookSupport.log("输入法适配已启用：" + app.getPackageName());
    }

    private static synchronized void installPlugin(ClassLoader loader, Application app, boolean optimize, boolean showAll) {
        if (LOADERS.contains(loader)) return;
        Class<?> manager = XposedHelpers.findClassIfExists(MANAGER, loader);
        if (manager == null) return;
        if (optimize) {
            installSupport(manager);
            installInsets(manager, app.getApplicationInfo().targetSdkVersion >= 35);
        }
        if (showAll) {
            installEnabledImeList(manager, app);
            installSwitchHook(app);
        }
        LOADERS.add(loader);
        HookSupport.log("输入法底部栏已适配");
    }

    private static void installEnabledImeList(Class<?> manager, Application app) {
        Method listMethod = null, popupMethod = null;
        for (Method method : manager.getDeclaredMethods()) {
            if ("getSupportIme".equals(method.getName()) && method.getParameterCount() == 0
                && List.class.isAssignableFrom(method.getReturnType())) listMethod = method;
            if ("clickSwitchIme".equals(method.getName()) && Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == void.class && method.getParameterCount() == 2
                && method.getParameterTypes()[0] == boolean.class && method.getParameterTypes()[1] == View.class)
                popupMethod = method;
        }
        if (listMethod == null || popupMethod == null) {
            HookSupport.log("未找到输入法切换列表方法");
            return;
        }
        XposedBridge.hookMethod(listMethod, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                ArrayList<InputMethodInfo> enabled = POPUP_INPUT_METHODS.get();
                if (param.hasThrowable() || !(param.getResult() instanceof List<?> original)
                    || enabled == null || enabled.isEmpty()) return;
                ArrayList<Object> merged = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (InputMethodInfo info : enabled) if (info != null && seen.add(info.getId())) merged.add(info);
                for (Object item : original) if (!(item instanceof InputMethodInfo)) merged.add(item);
                param.setResult(merged);
                HookSupport.log("底部栏已读取已启用输入法：" + seen.size());
            }
        });
        XposedBridge.hookMethod(popupMethod, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(OPENING_POPUP.get()) || !Options.enabled(app, Options.IME_SWITCH_LIST)
                    || !(param.args[1] instanceof View anchor) || !canRequestPopup(manager)) return;
                InputMethodService service = currentInputService(manager);
                EditorInfo editor = service == null ? null : service.getCurrentInputEditorInfo();
                if (service == null || editor == null || !service.getCurrentInputStarted()
                    || !service.isInputViewShown()) return;
                param.setResult(null);
                if (popupPending) {
                    cancelPendingPopup();
                    return;
                }
                popupPending = true;
                long request = ++popupGeneration;
                boolean left = (boolean) param.args[0];
                WeakReference<View> anchorRef = new WeakReference<>(anchor);
                WeakReference<InputMethodService> serviceRef = new WeakReference<>(service);
                WeakReference<EditorInfo> editorRef = new WeakReference<>(editor);
                requestSystemUi(app, ImeListReceiver.ACTION, null, result -> {
                    if (request != popupGeneration) return;
                    popupPending = false;
                    View currentAnchor = anchorRef.get();
                    InputMethodService currentService = serviceRef.get();
                    EditorInfo currentEditor = editorRef.get();
                    if (currentAnchor == null || !currentAnchor.isAttachedToWindow() || !currentAnchor.isShown()
                        || currentAnchor.getWindowVisibility() != View.VISIBLE
                        || currentService == null || !currentService.getCurrentInputStarted()
                        || !currentService.isInputViewShown() || currentEditor == null
                        || currentService.getCurrentInputEditorInfo() != currentEditor
                        || !isCurrentIme(app)) return;
                    ArrayList<InputMethodInfo> enabled = result == null ? null
                        : result.getParcelableArrayList(ImeListReceiver.RESULT, InputMethodInfo.class);
                    boolean listEnabled = Options.enabled(app, Options.IME_SWITCH_LIST);
                    if (!listEnabled) enabled = null;
                    if (listEnabled && (enabled == null || enabled.isEmpty())) {
                        Toast.makeText(app, "输入法列表暂不可用，请重试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        if (enabled != null) POPUP_INPUT_METHODS.set(enabled);
                        OPENING_POPUP.set(true);
                        XposedHelpers.callStaticMethod(manager, "clickSwitchIme", left, currentAnchor);
                    } catch (Throwable error) { HookSupport.error("打开输入法列表", error); }
                    finally { OPENING_POPUP.remove(); POPUP_INPUT_METHODS.remove(); }
                });
            }
        });
        XposedBridge.hookAllMethods(manager, "onWindowHidden", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { cancelPendingPopup(); }
        });
        HookSupport.log("输入法切换列表已适配");
    }

    private static void cancelPendingPopup() {
        if (popupPending) { popupPending = false; popupGeneration++; }
    }

    private static synchronized void installSwitchHook(Application app) {
        if (switchHookInstalled) return;
        XposedHelpers.findAndHookMethod(InputMethodService.class, "switchInputMethod", String.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isBottomPopupClick()) return;
                    String target = (String) param.args[0];
                    if (!Options.enabled(app, Options.IME_SWITCH_LIST) && isLocallyVisibleIme(app, target)) return;
                    param.setResult(null);
                    if (!Options.enabled(app, Options.IME_SWITCH_LIST)) {
                        Toast.makeText(app, "输入法切换失败，请重试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    requestSystemUi(app, ImeListReceiver.SWITCH, target, result -> {
                        if (result == null && isCurrentIme(app))
                            Toast.makeText(app, "输入法切换失败，请重试", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        switchHookInstalled = true;
    }

    private static boolean isLocallyVisibleIme(Application app, String target) {
        if (target == null) return false;
        try {
            InputMethodManager manager = app.getSystemService(InputMethodManager.class);
            if (manager != null) for (InputMethodInfo info : manager.getEnabledInputMethodList())
                if (target.equals(info.getId())) return true;
        } catch (Throwable error) { HookSupport.error("检查输入法", error); }
        return false;
    }

    private static boolean isBottomPopupClick() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().startsWith("com.miui.inputmethod.InputMethodSwitchPopupView$")
                && frame.getMethodName().equals("clickItem")) return true;
        }
        return false;
    }

    private static boolean canRequestPopup(Class<?> manager) {
        try {
            Object existing = XposedHelpers.getStaticObjectField(manager, "sSwitchWindow");
            if (existing instanceof PopupWindow popup && (popup.isShowing()
                || XposedHelpers.getBooleanField(existing, "isOutSideEvent"))) return false;
            Object clipboard = XposedHelpers.getStaticObjectField(manager, "sClipboardWindow");
            return !(clipboard instanceof PopupWindow popup && popup.isShowing());
        } catch (Throwable error) { HookSupport.error("检查输入法弹窗", error); return false; }
    }

    private static InputMethodService currentInputService(Class<?> manager) {
        try {
            Object helper = XposedHelpers.getStaticObjectField(manager, "sBottomViewHelper");
            Object service = helper == null ? null : XposedHelpers.getObjectField(helper, "mInputMethodService");
            return service instanceof InputMethodService input ? input : null;
        } catch (Throwable error) { HookSupport.error("检查当前输入法", error); return null; }
    }

    private static boolean isCurrentIme(Application app) {
        String selected = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName component = selected == null ? null : ComponentName.unflattenFromString(selected);
        return component != null && app.getPackageName().equals(component.getPackageName());
    }

    private static void requestSystemUi(Application app, String action, String target, Consumer<Bundle> callback) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                HookSupport.log("输入法请求超时");
                callback.accept(null);
            }
        };
        MAIN.postDelayed(timeout, REQUEST_TIMEOUT_MS);
        try {
            Intent intent = new Intent(action)
                .setPackage("com.android.systemui")
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(ImeListReceiver.EXPIRES, SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS);
            if (target != null) intent.putExtra(ImeListReceiver.TARGET, target);
            BroadcastOptions options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
            app.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent result) {
                    if (!finished.compareAndSet(false, true)) return;
                    MAIN.removeCallbacks(timeout);
                    Bundle extras = getResultExtras(false);
                    callback.accept(getResultCode() == Activity.RESULT_OK ? extras : null);
                }
            }, MAIN, Activity.RESULT_CANCELED, null, null);
        } catch (Throwable error) {
            HookSupport.error("请求输入法列表", error);
            if (finished.compareAndSet(false, true)) {
                MAIN.removeCallbacks(timeout);
                MAIN.post(() -> callback.accept(null));
            }
        }
    }


    private static void installSupport(Class<?> owner) {
        Field field = null;
        try {
            Field candidate = XposedHelpers.findField(owner, "sIsImeSupport");
            if (candidate.getType() == int.class && Modifier.isStatic(candidate.getModifiers())) {
                candidate.setInt(null, 1);
                field = candidate;
            }
        } catch (Throwable ignored) { /* Some versions use only the support method. */ }
        final Field supportField = field;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getReturnType() == boolean.class
                && ("isImeSupport".equals(method.getName()) || "isXiaoAiEnable".equals(method.getName()))) {
                XposedBridge.hookMethod(method,
                    XC_MethodReplacement.returnConstant("isImeSupport".equals(method.getName())));
            } else if (supportField != null && "addMiuiBottomView".equals(method.getName())) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try { supportField.setInt(null, 1); }
                        catch (IllegalAccessException error) { HookSupport.error("输入法支持状态", error); }
                    }
                });
            }
        }
    }

    private static void installNavigationColor(Class<?> owner) {
        boolean hasColorMethod = false;
        for (Method method : owner.getDeclaredMethods())
            if ("customizeBottomViewColor".equals(method.getName()) && Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 4) hasColorMethod = true;
        if (!hasColorMethod) return;
        final int[] color = {0};
        Runnable update = () -> {
            if (color[0] == 0) return;
            try {
                int inverted = -1 - color[0];
                XposedHelpers.callStaticMethod(owner, "customizeBottomViewColor", true, color[0],
                    inverted | 0xff000000, inverted | 0x66000000);
            } catch (Throwable error) { HookSupport.error("输入法底部栏颜色", error); }
        };
        try {
            XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow", null,
                "setNavigationBarColor", int.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        int next = (int) param.args[0];
                        if (next != 0) { color[0] = next; update.run(); }
                    }
                });
            XposedBridge.hookAllMethods(owner, "addMiuiBottomView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { update.run(); }
            });
        } catch (Throwable error) { HookSupport.error("输入法底部栏颜色适配", error); }
    }

    private static synchronized void installInsets(Class<?> manager, boolean filterNavigationInsets) {
        XposedBridge.hookAllMethods(manager, "addMiuiBottomView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 5 && param.args[3] instanceof ViewGroup frame
                    && param.args[5] instanceof ViewGroup area) INSETS.bind(frame, area);
            }
        });
        XposedBridge.hookAllMethods(manager, "onWindowShown", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { INSETS.request(); }
        });
        if (!filterNavigationInsets || viewHooksInstalled) return;
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View view && param.args[0] instanceof WindowInsets insets)
                    param.args[0] = INSETS.forInputView(view, insets);
            }
        };
        XposedHelpers.findAndHookMethod(View.class, "dispatchApplyWindowInsets", WindowInsets.class, filter);
        XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchApplyWindowInsets", WindowInsets.class, filter);
        viewHooksInstalled = true;
    }

    /** The MIUI bottom view already reserves the navigation area for modern IMEs. */
    private static final class KeyboardInsets {
        private WeakReference<ViewGroup> frame = new WeakReference<>(null);
        private WeakReference<ViewGroup> area = new WeakReference<>(null);
        private ViewTreeObserver observer;
        private boolean ready;
        private int loggedBottom;
        private final ViewTreeObserver.OnGlobalLayoutListener layout = () -> {
            ViewGroup bottom = area.get();
            boolean now = bottom != null && bottom.getVisibility() == View.VISIBLE && bottom.getChildCount() > 0;
            if (now) removeDuplicateBottomMargin(bottom);
            if (now && !ready) request();
            ready = now;
        };
        void bind(ViewGroup inputFrame, ViewGroup bottom) {
            clear();
            frame = new WeakReference<>(inputFrame);
            area = new WeakReference<>(bottom);
            observer = bottom.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(layout);
            request();
        }
        void request() {
            ViewGroup inputFrame = frame.get();
            if (inputFrame != null) inputFrame.requestApplyInsets();
        }
        private void removeDuplicateBottomMargin(ViewGroup bottom) {
            if (!(bottom.getParent() instanceof ViewGroup parent)
                || !(bottom.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)
                || params.bottomMargin <= 0) return;
            View root = bottom.getRootView();
            WindowInsets insets = bottom.getRootWindowInsets();
            if (root == null || insets == null || root.getHeight() <= 0 || parent.getHeight() <= 0) return;
            int[] rootPosition = new int[2], parentPosition = new int[2];
            root.getLocationOnScreen(rootPosition);
            parent.getLocationOnScreen(parentPosition);
            int reserved = rootPosition[1] + root.getHeight() - parentPosition[1] - parent.getHeight();
            int navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
            // The parent already ends above the navigation bar; an additional bottom margin
            // leaves the underlying app visible between the keyboard and the navigation bar.
            if (Math.abs(reserved - navigation) > 2) return;
            params.bottomMargin = 0;
            bottom.setLayoutParams(params);
            HookSupport.log("已移除输入法底部重复留白");
        }
        WindowInsets forInputView(View view, WindowInsets original) {
            ViewGroup inputFrame = frame.get(), bottom = area.get();
            if (inputFrame == null || bottom == null || view.getParent() != inputFrame
                || bottom.getVisibility() != View.VISIBLE || bottom.getChildCount() == 0) return original;
            int type = WindowInsets.Type.navigationBars();
            Insets visible = original.getInsets(type), stable = original.getInsetsIgnoringVisibility(type);
            if (visible.bottom == 0 && stable.bottom == 0) return original;
            int bottomInset = Math.max(visible.bottom, stable.bottom);
            if (loggedBottom != bottomInset) {
                loggedBottom = bottomInset;
                HookSupport.log("输入法重复导航区域已调整：" + bottomInset);
            }
            return new WindowInsets.Builder(original)
                .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
                .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, 0))
                .build();
        }
        private void clear() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layout);
            ViewGroup bottom = area.get();
            if (bottom != null && bottom.getViewTreeObserver() != observer && bottom.getViewTreeObserver().isAlive())
                bottom.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
            observer = null;
            frame.clear();
            area.clear();
            ready = false;
        }
    }
}
