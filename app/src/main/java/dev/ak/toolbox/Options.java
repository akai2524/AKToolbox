package dev.ak.toolbox;

import android.content.Context;
import android.provider.Settings;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Options {
    private Options() {}
    public static final String COPY = "ak_toolbox_screenshot_copy";
    public static final String HIDE_STATUS = "ak_toolbox_screenshot_hide_status";
    public static final String WEATHER = "ak_toolbox_notification_weather";
    public static final String CITY = "ak_toolbox_notification_city";
    public static final String HIDE_EDIT = "ak_toolbox_hide_edit";
    public static final String HEADSET_SOUND = "ak_toolbox_headset_sound";
    public static final String THIRD_PARTY_IME = "ak_toolbox_third_party_ime";
    public static final String IME_SWITCH_LIST = "ak_toolbox_ime_switch_list";
    public static final String REAR_PAYMENT = "ak_toolbox_rear_payment";
    public static final String REAR_TRANSIT = "ak_toolbox_rear_transit";
    public static final Map<String, String> LABELS;
    static {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        names.put(COPY, "截图自动复制图片");
        names.put(HIDE_STATUS, "截图隐藏状态栏");
        names.put(WEATHER, "通知中心显示天气");
        names.put(CITY, "显示天气所在地区");
        names.put(HIDE_EDIT, "隐藏控制中心编辑按钮");
        names.put(HEADSET_SOUND, "耳机音效自动切换");
        names.put(THIRD_PARTY_IME, "第三方输入法全面屏优化");
        names.put(IME_SWITCH_LIST, "底部栏显示所有已启用输入法");
        names.put(REAR_PAYMENT, "付款码自动显示到背屏");
        names.put(REAR_TRANSIT, "乘车码自动显示到背屏");
        LABELS = java.util.Collections.unmodifiableMap(names);
    }
    public static boolean enabled(Context context, String key) {
        if (context == null) return false;
        try {
            int defaultValue = (REAR_PAYMENT.equals(key) || REAR_TRANSIT.equals(key)) ? 0 : 1;
            return Settings.Secure.getInt(context.getContentResolver(), key, defaultValue) != 0;
        }
        catch (Throwable ignored) { return false; }
    }
}
