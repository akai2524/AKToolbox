package dev.ak.toolbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import dev.ak.toolbox.payment.PaymentSession;

public final class MainActivity extends Activity {
    private int background, surface, ink, muted, accent, soft;
    private LinearLayout body;
    private TextView connection, state, explanation, message;
    private Button toggle, restart;
    private final Map<String, Switch> options = new HashMap<>();
    private boolean rendering, connected, busy, restarting;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        autoConfigureBatteryAndAutostart(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#111318" : "#F5F6F8");
        surface = Color.parseColor(dark ? "#1C1F25" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#F1F3F8" : "#17191E");
        muted = Color.parseColor(dark ? "#A4ACBA" : "#717785");
        accent = Color.parseColor(dark ? "#91ADFF" : "#3868E8");
        soft = Color.parseColor(dark ? "#26314C" : "#EAF0FF");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(dp(20) + bars.left, bars.top + dp(18), dp(20) + bars.right, bars.bottom + dp(28));
            return insets;
        });
        TextView title = text("AK工具箱", 30, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.addView(title);
        body.addView(text(getDeviceModel() + " · " + getOsVersion(), 14, muted), spaced(0, 5, 0, 18));
        connection = text("正在检查模块连接", 13, accent);
        connection.setPadding(dp(14), dp(10), dp(14), dp(10));
        connection.setBackground(round(soft, 16));
        connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(connection, spaced(0, 0, 0, 16));

        LinearLayout gapps = card();
        gapps.addView(text("Google服务", 25, ink), spaced(18, 17, 18, 3));
        state = text("正在读取状态", 16, accent);
        gapps.addView(state, spaced(18, 0, 18, 7));
        explanation = text("关闭核心 Google 服务与商店，开启时恢复原状态。", 13, muted);
        gapps.addView(explanation, spaced(18, 0, 18, 12));
        toggle = button("切换 Google服务", true);
        toggle.setEnabled(false);
        toggle.setOnClickListener(v -> toggleGapps());
        gapps.addView(toggle, spaced(14, 0, 14, 4));
        Button tile = button("添加Google服务磁贴", false);
        tile.setOnClickListener(v -> addGoogleTile());
        gapps.addView(tile, spaced(14, 0, 14, 12));
        body.addView(gapps, spaced(0, 0, 0, 22));

        section("背屏付款码");
        LinearLayout payment = card();
        option(payment, Options.REAR_PAYMENT, "打开微信或支付宝付款码时显示二维码和条形码，退出后自动隐藏");
        TextView paymentHelp = text("需要 Root 权限。已通过 Root 自动优化后台电池与自启动策略，并在 LSPosed 勾选微信与支付宝后重启这两个应用。付款码只在本机显示，不保存图片。", 12, muted);
        paymentHelp.setLineSpacing(dp(3), 1f);
        payment.addView(paymentHelp, spaced(18, 0, 18, 16));
        body.addView(payment, spaced(0, 0, 0, 22));

        section("输入法");
        LinearLayout inputMethods = card();
        option(inputMethods, Options.THIRD_PARTY_IME, "在 LSPosed 勾选对应输入法，更改后重启输入法生效");
        option(inputMethods, Options.IME_SWITCH_LIST, "在 LSPosed 勾选使用底部栏的输入法，更改后重启输入法生效");
        body.addView(inputMethods, spaced(0, 0, 0, 22));

        section("音效");
        LinearLayout sound = card();
        option(sound, Options.HEADSET_SOUND, "连接耳机切换原声，全部断开后切换杜比全景声");
        body.addView(sound, spaced(0, 0, 0, 22));

        section("截屏");
        LinearLayout screenshots = card();
        option(screenshots, Options.COPY, "普通截图与长截图均复制图片，保留相册文件");
        option(screenshots, Options.HIDE_STATUS, "隐藏时间、信号等内容，保留原图尺寸与顶部背景");
        body.addView(screenshots, spaced(0, 0, 0, 22));

        section("通知与控制中心");
        LinearLayout notifications = card();
        option(notifications, Options.WEATHER, "仅在通知中心日期旁显示，数据来自小米天气");
        option(notifications, Options.CITY, "跟随小米天气当前城市");
        option(notifications, Options.HIDE_EDIT, "隐藏控制中心编辑按钮");
        body.addView(notifications, spaced(0, 0, 0, 14));

        restart = button("重启系统界面", false);
        restart.setEnabled(false);
        restart.setOnClickListener(v -> restartSystemUi());
        body.addView(restart, spaced(0, 0, 0, 10));

        message = text("", 13, muted);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(message, spaced(4, 0, 4, 8));
        Button manager = button("打开 LSPosed", false);
        manager.setOnClickListener(v -> openManager());
        body.addView(manager, spaced(0, 0, 0, 12));
        TextView footer = text("首次启用或修改作用域后需重启手机。卸载前可先开启 Google服务，恢复核心服务原状态。", 12, muted);
        footer.setLineSpacing(dp(4), 1f);
        body.addView(footer, spaced(4, 0, 4, 0));
    }
    @Override protected void onResume() {
        super.onResume();
        if (!restarting) BridgeClient.send(this, BridgeClient.QUERY, null, this::showResult);
    }
    private void option(LinearLayout group, String key, String description) {
        Switch control = row(group, Options.LABELS.get(key), description, Options.enabled(this, key));
        options.put(key, control);
        control.setEnabled(false);
        control.setOnCheckedChangeListener((v, checked) -> {
            if (rendering || !connected) return;
            BridgeClient.send(this, BridgeClient.SET_OPTION,
                new Intent().putExtra("key", key).putExtra("value", checked), this::showResult);
        });
    }
    private void showResult(Intent result) {
        if (isFinishing() || isDestroyed()) return;
        connected = result.getBooleanExtra("connected", false);
        connection.setText(connected ? "模块已连接" : "模块未连接");
        String value = result.getStringExtra("state");
        state.setText(!connected ? "等待模块连接" : switch (value == null ? "" : value) {
            case "ON" -> "Google服务已开启";
            case "OFF" -> "Google服务已关闭";
            case "PARTIAL" -> "部分 Google 组件已停用";
            default -> "缺少 Google 核心组件";
        });
        toggle.setText("OFF".equals(value) ? "开启 Google服务" : "关闭 Google服务");
        toggle.setEnabled(connected && !busy && !restarting && !"MISSING".equals(value));
        restart.setEnabled(connected && !busy && !restarting);
        explanation.setText("OFF".equals(value) ? "核心服务已停用。再次开启时恢复先前记录的启用状态。"
            : "关闭核心 Google 服务与商店，开启时恢复原状态。");
        rendering = true;
        for (Map.Entry<String, Switch> entry : options.entrySet()) {
            entry.getValue().setEnabled(connected && !restarting);
            if (connected) entry.getValue().setChecked(result.getBooleanExtra(entry.getKey(), !Options.REAR_PAYMENT.equals(entry.getKey())));
        }
        if (connected && !result.getBooleanExtra(Options.REAR_PAYMENT, false)) PaymentSession.stopAll();
        options.get(Options.CITY).setEnabled(connected && !restarting && options.get(Options.WEATHER).isChecked());
        rendering = false;
        String error = result.getStringExtra("error");
        message.setText(error != null ? error : "设置已同步。界面设置若未更新，可点击重启系统界面。");
    }
    private void toggleGapps() {
        if (!connected || busy || restarting) return;
        busy = true;
        toggle.setEnabled(false);
        restart.setEnabled(false);
        BridgeClient.send(this, BridgeClient.TOGGLE, null, result -> {
                busy = false; showResult(result);
            });
    }
    private void restartSystemUi() {
        if (!connected || busy || restarting) return;
        restarting = true;
        restart.setEnabled(false);
        toggle.setEnabled(false);
        for (Switch option : options.values()) option.setEnabled(false);
        BridgeClient.send(this, BridgeClient.RESTART_SYSTEM_UI, null, result -> {
            if (!result.getBooleanExtra("restarting", false) || result.hasExtra("error")) {
                restarting = false;
                showResult(result);
                return;
            }
            connection.setText("系统界面正在重启");
            message.setText("屏幕会短暂刷新，重启后自动检查连接。");
            body.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                restarting = false;
                BridgeClient.send(this, BridgeClient.QUERY, null, this::showResult);
            }, 2000);
        });
    }
    private void addGoogleTile() {
        BridgeClient.send(this, BridgeClient.ADD_GOOGLE_TILE, null, result -> {
            if (isFinishing() || isDestroyed()) return;
            String error = result.getStringExtra("error");
            if (!result.getBooleanExtra("connected", false) || error != null) {
                toast(error != null ? error : "模块未连接，无法添加 Google服务磁贴");
            } else {
                toast("正在添加 Google服务磁贴");
            }
        });
    }
    private void openManager() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (intent == null) toast("请从 LSPosed 通知或桌面快捷方式打开管理器");
        else startActivity(intent);
    }
    private void section(String title) {
        TextView heading = text(title, 14, muted);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.addView(heading, spaced(4, 0, 4, 9));
    }
    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(round(surface, 23));
        return view;
    }
    private Switch row(LinearLayout group, String title, String description, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(18), dp(13));
        row.setMinimumHeight(dp(76));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, ink));
        TextView detail = text(description, 12, muted);
        detail.setLineSpacing(dp(2), 1f);
        labels.addView(detail, spaced(0, 4, 10, 0));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setContentDescription(title + "，" + description);
        control.setChecked(checked);
        control.setShowText(false);
        control.setMinimumHeight(dp(48));
        row.addView(control);
        row.setOnClickListener(v -> { if (control.isEnabled()) control.setChecked(!control.isChecked()); });
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }
    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? background : accent);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? accent : soft));
        return button;
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private LinearLayout.LayoutParams spaced(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    public static void autoConfigureBatteryAndAutostart(Context context) {
        String pkg = context.getPackageName();
        new Thread(() -> {
            try {
                String cmd = "dumpsys deviceidle whitelist +" + pkg + "\n"
                        + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow\n"
                        + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow\n"
                        + "cmd appops set " + pkg + " 10008 allow\n"
                        + "content insert --uri content://com.miui.powerkeeper.configure/userTable --bind userId:i:0 --bind pkgName:s:" + pkg + " --bind bgControl:s:noRestrict\n";
                Process p = new ProcessBuilder("su", "-c", cmd).start();
                p.waitFor();
            } catch (Throwable ignored) { }
        }, "AK-battery-opt").start();
    }

    private static String getDeviceModel() {
        String market = getProp("ro.product.marketname");
        if (!market.isEmpty()) return market;
        String model = Build.MODEL;
        return model != null && !model.isEmpty() ? model : "小米设备";
    }

    private static String getOsVersion() {
        String inc = getProp("ro.mi.os.version.incremental");
        if (!inc.isEmpty()) return inc;
        String binc = Build.VERSION.INCREMENTAL;
        if (binc != null && !binc.isEmpty()) return binc;
        String os = getProp("ro.mi.os.version.name");
        if (!os.isEmpty()) return os;
        return "Android " + Build.VERSION.RELEASE;
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
