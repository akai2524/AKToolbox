package dev.ak.toolbox.hook;

import android.app.Application;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.ak.toolbox.ScreenTimeFilterProvider;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Filters values seen by Xiaomi Settings only; system usage storage is never modified. */
final class ScreenTimeFilter {
    private static volatile Set<String> targets = Collections.emptySet();
    private static long nextRefresh;
    private ScreenTimeFilter() {}

    static void install(ClassLoader loader) {
        hookFramework();
        hookModel(loader, "com.xiaomi.misettings.features.screentime.data.model.DeviceAppUsage");
        hookModel(loader, "com.xiaomi.misettings.features.screentime.data.model.DeviceAppUsageKt");
        hookHistoryInput(loader);
    }

    private static synchronized Set<String> selected() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextRefresh) return targets;
        nextRefresh = now + 2000;
        try {
            Class<?> thread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Application app = (Application) XposedHelpers.callStaticMethod(thread, "currentApplication");
            if (app == null) return targets;
            Bundle result = app.getContentResolver().call(ScreenTimeFilterProvider.URI, "read", null, null);
            if (result == null || !result.getBoolean(ScreenTimeFilterProvider.ENABLED, false)) {
                targets = Collections.emptySet();
            } else {
                ArrayList<String> packages = result.getStringArrayList(ScreenTimeFilterProvider.PACKAGES);
                targets = packages == null ? Collections.emptySet()
                    : Collections.unmodifiableSet(new HashSet<>(packages));
            }
        } catch (Throwable error) {
            // A provider failure must not disrupt Xiaomi Settings or keep an outdated filter active.
            targets = Collections.emptySet();
        }
        return targets;
    }

    private static void hookFramework() {
        for (String name : new String[] { "getTotalTimeInForeground", "getTotalTimeVisible" }) {
            try {
                Method method = UsageStats.class.getDeclaredMethod(name);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.thisObject instanceof UsageStats stats && selected().contains(stats.getPackageName()))
                            p.setResult(0L);
                    }
                });
            } catch (Throwable error) { HookSupport.error("屏幕时间统计接口 " + name, error); }
        }
        for (Method method : UsageStatsManager.class.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("queryUsageStats") && !name.equals("queryAndAggregateUsageStats")) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable()) return;
                        Set<String> hidden = selected();
                        if (hidden.isEmpty()) return;
                        Object filtered = filterResult(p.getResult(), hidden);
                        if (filtered != p.getResult() && method.getReturnType().isInstance(filtered)) p.setResult(filtered);
                    }
                });
            } catch (Throwable error) { HookSupport.error("屏幕时间查询 " + name, error); }
        }
        try {
            XposedHelpers.findAndHookMethod(UsageEvents.class, "getNextEvent", UsageEvents.Event.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!Boolean.TRUE.equals(p.getResult()) || !(p.args[0] instanceof UsageEvents.Event event)) return;
                        if (!selected().contains(event.getPackageName())) return;
                        try {
                            XposedHelpers.setIntField(event, "mEventType", 0);
                            XposedHelpers.setObjectField(event, "mPackage", "android");
                        } catch (Throwable ignored) { }
                    }
                });
        } catch (Throwable error) { HookSupport.error("屏幕时间事件", error); }
    }

    private static void hookModel(ClassLoader loader, String className) {
        try {
            Class<?> cls = Class.forName(className, false, loader);
            for (Method method : cls.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers()) || method.isSynthetic()) continue;
                Class<?> type = method.getReturnType();
                if (!(Map.class.isAssignableFrom(type) && type.isAssignableFrom(HashMap.class))
                        && !(List.class.isAssignableFrom(type) && type.isAssignableFrom(ArrayList.class))) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable()) return;
                            Set<String> hidden = selected();
                            if (hidden.isEmpty()) return;
                            Object filtered = filterResult(p.getResult(), hidden);
                            if (filtered != p.getResult() && type.isInstance(filtered)) p.setResult(filtered);
                        }
                    });
                } catch (Throwable error) { HookSupport.error("屏幕时间模型 " + method.getName(), error); }
            }
        } catch (ClassNotFoundException ignored) {
            HookSupport.log("屏幕时间模型不匹配：" + className);
        } catch (Throwable error) { HookSupport.error("屏幕时间模型", error); }
    }

    private static void hookHistoryInput(ClassLoader loader) {
        try {
            Class<?> cls = Class.forName("com.xiaomi.misettings.features.screentime.data.local.entity.AppUsageEntityKt", false, loader);
            for (Method method : cls.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!method.getName().equals("asDeviceAppUsage") || args.length == 0
                        || !Collection.class.isAssignableFrom(args[0]) || !args[0].isAssignableFrom(ArrayList.class)) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Set<String> hidden = selected();
                        if (hidden.isEmpty() || !(p.args[0] instanceof Collection<?> input)) return;
                        ArrayList<Object> filtered = new ArrayList<>();
                        for (Object item : input) if (!hidden.contains(packageName(item))) filtered.add(item);
                        if (filtered.size() != input.size()) p.args[0] = filtered;
                    }
                });
            }
        } catch (ClassNotFoundException ignored) {
            HookSupport.log("屏幕时间历史记录接口不匹配");
        } catch (Throwable error) { HookSupport.error("屏幕时间历史记录", error); }
    }

    private static Object filterResult(Object value, Set<String> hidden) {
        if (value instanceof Map<?, ?> map) {
            HashMap<Object, Object> copy = null;
            for (String pkg : hidden) if (map.containsKey(pkg)) {
                if (copy == null) copy = new HashMap<>(map);
                copy.remove(pkg);
            }
            return copy == null ? value : copy;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) if (!hidden.contains(packageName(item))) copy.add(item);
            return copy.size() == list.size() ? value : copy;
        }
        return value;
    }

    private static String packageName(Object item) {
        if (item instanceof UsageStats stats) return stats.getPackageName();
        if (item instanceof String text) return text;
        if (item == null || !item.getClass().getName().startsWith("com.xiaomi.misettings.features.screentime.")) return null;
        try {
            Method getter = item.getClass().getMethod("getPackageName");
            Object value = getter.invoke(item);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException ignored) { return null; }
    }
}
