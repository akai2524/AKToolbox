/* Notification header placement/provider contract adapted from HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.akai.toolbox.hook;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.akai.toolbox.Options;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NotificationWeather {
    private static final Map<TextView, WeakReference<WeatherText>> LABELS = new WeakHashMap<>();
    private static final Set<Class<?>> CALLBACKS = Collections.newSetFromMap(new WeakHashMap<>());
    private NotificationWeather() {}
    public static void install(ClassLoader loader) {
        Class<?> combined = XposedHelpers.findClass("com.android.systemui.controlcenter.shade.CombinedHeaderController", loader);
        XposedBridge.hookAllConstructors(combined, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (HookSupport.systemContext == null) return;
                try {
                    // Deliberately target only NOTIFICATION header fields, never control-center dates.
                    add(XposedHelpers.getObjectField(p.thisObject, "notificationDateTime"));
                    add(XposedHelpers.getObjectField(p.thisObject, "notificationHorizontalTime"));
                } catch (Throwable error) { HookSupport.error("天气布局", error); }
            }
        });
        XposedBridge.hookAllMethods(combined, "onSwitchProgressChanged", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { sync(false); }
        });
        Class<?> header = XposedHelpers.findClass("com.android.systemui.qs.MiuiNotificationHeaderView", loader);
        for (String name : new String[]{"updateHeaderResources", "updateResources", "updateLayout"}) {
            XposedBridge.hookAllMethods(header, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { sync(true); }
            });
        }
        Class<?> expand = XposedHelpers.findClass("com.android.systemui.controlcenter.shade.NotificationHeaderExpandController", loader);
        XposedBridge.hookAllConstructors(expand, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Class<?> callback = XposedHelpers.getObjectField(p.thisObject, "notificationCallback").getClass();
                    if (!CALLBACKS.add(callback)) return;
                    XposedBridge.hookAllMethods(callback, "onExpansionChanged", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam args) { sync(false); }
                    });
                    XposedBridge.hookAllMethods(callback, "onAppearanceChanged", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam args) {
                            boolean visible = (boolean) args.args[0];
                            boolean animate = (boolean) args.args[1];
                            for (Map.Entry<TextView, WeatherText> entry : entries()) {
                                WeatherText weather = entry.getValue();
                                weather.animate().cancel();
                                weather.animate().alpha(visible ? 1f : 0f).setDuration(animate ? 200 : 0).start();
                            }
                        }
                    });
                } catch (Throwable error) { HookSupport.error("天气展开动画", error); }
            }
        });
        HookSupport.log("通知中心日期旁天气已适配");
    }
    private static void add(Object value) throws Exception {
        if (!(value instanceof TextView source)
            || !(source.getParent() instanceof ViewGroup parent)) return;
        WeakReference<WeatherText> existing = LABELS.get(source);
        if (existing != null && existing.get() != null) return;
        ClassLoader loader = parent.getClass().getClassLoader();
        Class<?> params = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", false, loader);
        WeatherText weather = new WeatherText(source.getContext());
        weather.setId(View.generateViewId());
        weather.setSingleLine(true);
        weather.setEllipsize(TextUtils.TruncateAt.END);
        weather.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        weather.setLayoutParams((ViewGroup.LayoutParams) params.getConstructor(int.class, int.class).newInstance(-2, -2));
        LABELS.put(source, new WeakReference<>(weather));
        parent.addView(weather, parent.indexOfChild(source) + 1);
        syncOne(source, weather);
        parent.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private boolean constrained;
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (source.getParent() != parent || weather.getParent() != parent) {
                    parent.removeOnLayoutChangeListener(this); return;
                }
                if (source.getWidth() > 0 && parent.getWidth() > 0) {
                    if (!constrained) {
                        constrained = true;
                        constrain(source, weather);
                    } else {
                        int margin = Math.round(15 * source.getResources().getDisplayMetrics().density);
                        int width = Math.max(1, parent.getWidth() - source.getRight() - margin);
                        if (weather.getMaxWidth() != width) weather.setMaxWidth(width);
                    }
                }
            }
        });
        parent.requestLayout();
    }
    private static void sync(boolean layout) {
        for (Map.Entry<TextView, WeatherText> entry : entries()) {
            try {
                syncOne(entry.getKey(), entry.getValue());
                if (layout) constrain(entry.getKey(), entry.getValue());
            } catch (Throwable error) { HookSupport.error("天气样式同步", error); }
        }
    }
    private static List<Map.Entry<TextView, WeatherText>> entries() {
        List<Map.Entry<TextView, WeatherText>> result = new ArrayList<>();
        for (Map.Entry<TextView, WeakReference<WeatherText>> entry : LABELS.entrySet()) {
            WeatherText weather = entry.getValue().get();
            if (entry.getKey() != null && weather != null)
                result.add(new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), weather));
        }
        return result;
    }
    private static void syncOne(TextView source, WeatherText weather) {
        weather.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        weather.setTypeface(source.getTypeface());
        weather.setTextColor(source.getTextColors());
        weather.setTranslationX(source.getTranslationX());
        weather.setTranslationY(source.getTranslationY());
        weather.setAlpha(source.getAlpha());
        weather.setVisibility(Options.enabled(source.getContext(), Options.WEATHER) ? source.getVisibility() : View.GONE);
        weather.setGravity(source.getGravity());
    }
    private static void constrain(TextView source, WeatherText weather) {
        if (!(source.getParent() instanceof ViewGroup parent) || weather.getParent() != parent) return;
        try {
            ClassLoader loader = parent.getClass().getClassLoader();
            Class<?> layout = Class.forName("androidx.constraintlayout.widget.ConstraintLayout", false, loader);
            if (!layout.isInstance(parent)) return;
            Class<?> cls = Class.forName("androidx.constraintlayout.widget.ConstraintSet", false, loader);
            Object constraints = cls.getConstructor().newInstance();
            cls.getMethod("clone", layout).invoke(constraints, parent);
            XposedHelpers.callMethod(constraints, "clear", weather.getId());
            XposedHelpers.callMethod(constraints, "constrainWidth", weather.getId(), -2);
            XposedHelpers.callMethod(constraints, "constrainHeight", weather.getId(), -2);
            int margin = Math.round(5 * source.getResources().getDisplayMetrics().density);
            XposedHelpers.callMethod(constraints, "connect", weather.getId(), 6, source.getId(), 7, margin);
            XposedHelpers.callMethod(constraints, "connect", weather.getId(), 3, source.getId(), 3);
            XposedHelpers.callMethod(constraints, "connect", weather.getId(), 4, source.getId(), 4);
            weather.setMaxWidth(Math.max(1, parent.getWidth() - source.getRight() - margin * 3));
            cls.getMethod("applyTo", layout).invoke(constraints, parent);
        } catch (Throwable error) { HookSupport.error("天气约束布局", error); }
    }
    private static final class WeatherText extends TextView {
        private static final Uri URI = Uri.parse("content://weather/weather");
        private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "AkaiToolbox-weather"));
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ContentObserver observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        private final Runnable update = this::query;
        private boolean registered;
        private int generation;
        WeatherText(Context context) { super(context); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!registered) {
                try {
                    getContext().getContentResolver().registerContentObserver(URI, true, observer);
                    for (String key : new String[]{Options.WEATHER, Options.CITY})
                        getContext().getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
                    registered = true;
                } catch (Throwable error) { HookSupport.error("天气订阅", error); }
            }
            refresh();
        }
        @Override protected void onDetachedFromWindow() {
            generation++;
            main.removeCallbacks(update);
            try { getContext().getContentResolver().unregisterContentObserver(observer); }
            catch (Throwable ignored) {}
            registered = false;
            super.onDetachedFromWindow();
        }
        private void refresh() {
            generation++;
            main.removeCallbacks(update);
            main.postDelayed(update, 200);
        }
        private void query() {
            int request = generation;
            if (!Options.enabled(getContext(), Options.WEATHER)) {
                setText(""); setVisibility(View.GONE); return;
            }
            IO.execute(() -> {
                String text = "";
                try (Cursor cursor = getContext().getContentResolver().query(URI,
                    new String[]{"city_name", "description", "temperature"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String city = cursor.getString(cursor.getColumnIndexOrThrow("city_name"));
                        String description = cursor.getString(cursor.getColumnIndexOrThrow("description"));
                        String temperature = cursor.getString(cursor.getColumnIndexOrThrow("temperature"));
                        text = (Options.enabled(getContext(), Options.CITY) ? safe(city) + " " : "")
                            + safe(description) + " " + safe(temperature);
                        text = text.trim();
                    }
                } catch (Throwable error) { HookSupport.error("天气数据不可用", error); }
                final String result = text;
                main.post(() -> {
                    if (!isAttachedToWindow() || generation != request) return;
                    setText(result);
                    setContentDescription(result);
                    for (Map.Entry<TextView, WeatherText> entry : entries())
                        if (entry.getValue() == this) syncOne(entry.getKey(), this);
                    if (result.isEmpty()) setVisibility(View.GONE);
                });
            });
        }
        private static String safe(String value) { return value == null ? "" : value; }
    }
}
