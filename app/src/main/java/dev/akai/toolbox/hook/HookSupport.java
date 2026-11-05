package dev.akai.toolbox.hook;

import android.app.Application;
import android.os.Build;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class HookSupport {
    private HookSupport() {}
    public static volatile Application systemContext;
    public static boolean supported() {
        if (Build.VERSION.SDK_INT != 37 || !"pandora".equals(Build.DEVICE)) return false;
        try {
            String build = (String) XposedHelpers.callStaticMethod(
                Class.forName("android.os.SystemProperties"), "get", "ro.mi.os.version.incremental", "");
            return "OS4.0.5.0.XBLCNXM".equals(build);
        } catch (Throwable error) { return false; }
    }
    public static void log(String message) { XposedBridge.log("AkaiToolbox: " + message); }
    public static void error(String feature, Throwable error) {
        log(feature + "：" + error.getClass().getSimpleName() + ": " + error.getMessage());
    }
}

