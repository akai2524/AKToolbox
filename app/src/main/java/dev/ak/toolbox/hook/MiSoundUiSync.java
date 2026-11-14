package dev.ak.toolbox.hook;

import android.app.Application;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Objects;

/** Refreshes the open MiSound selector after the native effects have actually changed. */
final class MiSoundUiSync {
    private static boolean installed;
    private static WeakReference<Object> visible = new WeakReference<>(null);
    private static String lastObserved;
    private MiSoundUiSync() { }

    static synchronized void install(Application app, ClassLoader loader) throws Throwable {
        if (installed) return;
        Class<?> fragment = XposedHelpers.findClass("h.i", loader);
        Method refresh = fragment.getDeclaredMethod("N0");
        if (refresh.getReturnType() != void.class) throw new IllegalStateException("音效界面方法不匹配");

        XposedBridge.hookMethod(fragment.getDeclaredMethod("onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { visible = new WeakReference<>(p.thisObject); }
        });
        XposedBridge.hookMethod(fragment.getDeclaredMethod("onPause"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (visible.get() == p.thisObject) visible.clear();
            }
        });
        lastObserved = Settings.Global.getString(app.getContentResolver(), "effect_implementer");
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                try {
                    String value = Settings.Global.getString(app.getContentResolver(), "effect_implementer");
                    if (Objects.equals(lastObserved, value)) return;
                    lastObserved = value;
                    Object target = visible.get();
                    if (target != null && Boolean.TRUE.equals(XposedHelpers.callMethod(target, "isResumed")))
                        XposedHelpers.callMethod(target, "N0");
                } catch (Throwable error) { HookSupport.error("刷新小米音效界面", error); }
            }
        };
        app.getContentResolver().registerContentObserver(
            Settings.Global.getUriFor("effect_implementer"), false, observer);
        installed = true;
        HookSupport.log("小米音效界面同步已适配");
    }
}
