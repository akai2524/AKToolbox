package dev.ak.toolbox.hook;

import android.app.Application;
import android.os.Build;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class HookSupport {
    private HookSupport() {}
    public static volatile Application systemContext;
    public static boolean supported() {
        if (Build.VERSION.SDK_INT < 34) return false;
        try {
            String os = (String) XposedHelpers.callStaticMethod(
                Class.forName("android.os.SystemProperties"), "get", "ro.mi.os.version.name", "");
            if (!os.isEmpty()) return true;
            String miui = (String) XposedHelpers.callStaticMethod(
                Class.forName("android.os.SystemProperties"), "get", "ro.miui.ui.version.name", "");
            if (!miui.isEmpty()) return true;
            return "pandora".equals(Build.DEVICE);
        } catch (Throwable error) { return true; }
    }
    public static void log(String message) { XposedBridge.log("AKToolbox: " + message); }
    public static void error(String feature, Throwable error) {
        log(feature + "：" + error.getClass().getSimpleName() + ": " + error.getMessage());
    }
}

