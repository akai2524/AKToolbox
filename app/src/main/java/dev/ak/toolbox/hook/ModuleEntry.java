package dev.ak.toolbox.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import dev.ak.toolbox.Options;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class ModuleEntry implements IXposedHookLoadPackage {
    private static final Set<ClassLoader> PLUGINS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean systemInstalled;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!HookSupport.supported()) return;
        try {
            if ("com.android.systemui".equals(param.packageName) && "com.android.systemui".equals(param.processName)) {
                // HyperOS 4 uses application.impl.SystemUIApplicationImpl. Hook the stable
                // framework lifecycle instead of depending on a vendor Application class name.
                XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Application app = (Application) p.args[0];
                            if (!"com.android.systemui".equals(app.getPackageName()) || systemInstalled) return;
                            try {
                                SystemBridge.install(app);
                                systemInstalled = true;
                                ClassLoader loader = app.getClassLoader();
                                safely("状态栏隐藏", () -> StatusBarCapture.install(loader));
                                safely("通知中心天气", () -> NotificationWeather.install(loader));
                                safely("控制中心插件", () -> installPluginLoader(loader));
                            } catch (Throwable error) { HookSupport.error("桥接初始化", error); }
                        }
                    });
            } else if ("com.miui.misound".equals(param.packageName) && "com.miui.misound".equals(param.processName)) {
                XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Application app = (Application) p.args[0];
                            if ("com.miui.misound".equals(app.getPackageName()))
                                safely("小米音效界面", () -> MiSoundUiSync.install(app, param.classLoader));
                        }
                    });
            } else if ("com.tencent.mm".equals(param.packageName) || "com.eg.android.AlipayGphone".equals(param.packageName)) {
                safely("付款码页面识别", () -> PaymentPageHooks.install(param));
            } else if ("com.miui.screenshot".equals(param.packageName)) {
                ScreenshotHooks.install(param.classLoader);
            } else if ("miui.systemui.plugin".equals(param.packageName)) {
                installPlugin(param.classLoader);
            } else {
                InputMethodHooks.installWhenApplicationStarts(param);
            }
        } catch (Throwable error) { HookSupport.error("模块加载", error); }
    }
    private interface Install { void run() throws Throwable; }
    private static void safely(String feature, Install action) {
        try { action.run(); } catch (Throwable error) { HookSupport.error(feature, error); }
    }
    private static void installPluginLoader(ClassLoader loader) {
        Class<?> factory = XposedHelpers.findClass(
            "com.android.systemui.shared.plugins.PluginInstance$PluginFactory", loader);
        XposedBridge.hookAllMethods(factory, "createPluginContext", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getResult() instanceof Context context && "miui.systemui.plugin".equals(context.getPackageName()))
                    safely("控制中心编辑按钮", () -> installPlugin(context.getClassLoader()));
            }
        });
    }
    private static synchronized void installPlugin(ClassLoader loader) throws Exception {
        if (PLUGINS.contains(loader)) return;
        Class<?> edit = Class.forName("miui.systemui.controlcenter.panel.main.qs.EditButtonController", false, loader);
        int count = 0;
        for (Method method : edit.getDeclaredMethods()) {
            if (!method.getName().equals("available") || method.getReturnType() != boolean.class) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Options.enabled(HookSupport.systemContext, Options.HIDE_EDIT)) p.setResult(false);
                }
            });
            count++;
        }
        if (count == 0) throw new IllegalStateException("编辑按钮方法不匹配");
        PLUGINS.add(loader);
        HookSupport.log("控制中心编辑按钮已适配");
    }
}
