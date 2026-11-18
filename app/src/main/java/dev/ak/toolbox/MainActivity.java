package dev.ak.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.ak.toolbox.core.RootAccessCheck;

public final class MainActivity extends Activity {
    private static final String PREFS_NAME = "ak_toolbox_ui_prefs";
    private static final String PREF_GOOGLE_TILE = "google_tile_enabled";
    private static final ExecutorService ROOT_CHECK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AK-root-check"); t.setDaemon(true); return t;
    });

    private int background, surface, ink, muted, accent, soft, divider, greenDot;
    private int cardBorder, softBorder;
    private View fixedHeader;
    private View headerDivider;
    private LinearLayout body;
    private TextView statusBadgeText, screenTimeCountText;
    private View statusDot;
    private Switch googleTileSwitch, hideLauncher;
    private final Map<String, Switch> options = new HashMap<>();
    private boolean rendering, connected, busy, restarting, paymentBusy;
    private boolean legacyRestoreAttempted;
    private boolean rootNoticeOpen, rootReady;
    private boolean updatingLauncher;
    private int rootCheckSequence;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;

        background = Color.parseColor(dark ? "#0D0E11" : "#F6F8FA");
        surface = Color.parseColor(dark ? "#16181D" : "#FFFFFF");
        cardBorder = Color.parseColor(dark ? "#262B35" : "#E5E7EB");
        ink = Color.parseColor(dark ? "#F2F4F8" : "#111827");
        muted = Color.parseColor(dark ? "#878F9E" : "#6B7280");
        accent = Color.parseColor("#0064EB");
        soft = Color.parseColor(dark ? "#191D24" : "#EBF0F8");
        softBorder = Color.parseColor(dark ? "#2A303C" : "#D7E1F0");
        divider = Color.parseColor(dark ? "#1E222A" : "#F3F4F6");
        greenDot = Color.parseColor(dark ? "#30D158" : "#34C759");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        fixedHeader = setupFixedHeader();
        root.addView(fixedHeader, new LinearLayout.LayoutParams(-1, -2));

        headerDivider = new View(this);
        headerDivider.setBackgroundColor(cardBorder);
        headerDivider.setVisibility(View.INVISIBLE);
        root.addView(headerDivider, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.setClipToPadding(false);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(scroll, scrollParams);
        setContentView(root);

        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            headerDivider.setVisibility(scrollY > dp(2) ? View.VISIBLE : View.INVISIBLE);
        });

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            fixedHeader.setPadding(dp(20) + bars.left, bars.top + dp(12), dp(20) + bars.right, dp(12));
            body.setPadding(dp(16) + bars.left, dp(12), dp(16) + bars.right, bars.bottom + dp(32));
            return insets;
        });

        // 1. 系统与服务
        section("系统与服务");
        LinearLayout systemCard = card();
        googleTileSwitch = switchRow(systemCard, "Google 服务磁贴", isGoogleTileEnabled(), (v, checked) -> {
            if (rendering) return;
            setGoogleTileEnabled(checked);
            if (checked) {
                addGoogleTile();
            }
        });
        addDivider(systemCard);
        screenTimeCountText = arrowRow(systemCard, "隐藏屏幕时间应用", "", v ->
            startActivity(new Intent(this, ScreenTimeFilterActivity.class)));
        body.addView(systemCard, spaced(0, 0, 0, 16));

        // 2. 多屏与副屏交互
        section("多屏与副屏交互");
        LinearLayout rearCard = card();
        option(rearCard, Options.REAR_PAYMENT, "付款码外屏投送");
        addDivider(rearCard);
        option(rearCard, Options.REAR_TRANSIT, "乘车码外屏投送");
        body.addView(rearCard, spaced(0, 0, 0, 16));

        // 3. 界面交互
        section("界面交互");
        LinearLayout uiCard = card();
        // 第三方输入法全面屏优化（底部切换栏开关跟随本选项并隐藏）
        Switch imeSwitch = switchRow(uiCard, "第三方输入法全面屏优化", Options.enabled(this, Options.THIRD_PARTY_IME), (v, checked) -> {
            if (rendering || !connected) return;
            BridgeClient.send(this, BridgeClient.SET_OPTION,
                new Intent().putExtra("key", Options.THIRD_PARTY_IME).putExtra("value", checked), this::showResult);
            BridgeClient.send(this, BridgeClient.SET_OPTION,
                new Intent().putExtra("key", Options.IME_SWITCH_LIST).putExtra("value", checked), null);
        });
        options.put(Options.THIRD_PARTY_IME, imeSwitch);
        imeSwitch.setEnabled(false);
        addDivider(uiCard);

        option(uiCard, Options.HEADSET_SOUND, "耳机音效自动切换");
        addDivider(uiCard);
        option(uiCard, Options.COPY, "截图自动复制");
        addDivider(uiCard);
        option(uiCard, Options.HIDE_STATUS, "截图隐藏状态栏");
        addDivider(uiCard);

        // 通知中心天气（天气跟随城市默认开启并隐藏独立选项）
        Switch weatherSwitch = switchRow(uiCard, "通知中心天气", Options.enabled(this, Options.WEATHER), (v, checked) -> {
            if (rendering || !connected) return;
            BridgeClient.send(this, BridgeClient.SET_OPTION,
                new Intent().putExtra("key", Options.WEATHER).putExtra("value", checked), this::showResult);
            if (checked) {
                BridgeClient.send(this, BridgeClient.SET_OPTION,
                    new Intent().putExtra("key", Options.CITY).putExtra("value", true), null);
            }
        });
        options.put(Options.WEATHER, weatherSwitch);
        weatherSwitch.setEnabled(false);
        addDivider(uiCard);

        option(uiCard, Options.HIDE_EDIT, "隐藏控制中心编辑按钮");
        body.addView(uiCard, spaced(0, 0, 0, 16));

        // 4. 管理与系统
        section("管理与系统");
        LinearLayout manageCard = card();
        hideLauncher = switchRow(manageCard, "隐藏桌面图标", isLauncherHidden(), (v, checked) -> {
            if (updatingLauncher) return;
            try {
                getPackageManager().setComponentEnabledSetting(launcherComponent(),
                    checked ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            } catch (RuntimeException failure) {
                updatingLauncher = true;
                hideLauncher.setChecked(isLauncherHidden());
                updatingLauncher = false;
                toast("无法修改桌面图标状态，请重试");
            }
        });
        addDivider(manageCard);
        arrowRow(manageCard, "重启系统界面", "", v -> restartSystemUi());
        addDivider(manageCard);
        arrowRow(manageCard, "LSPosed 模块管理器", "", v -> openManager());
        addDivider(manageCard);
        arrowRow(manageCard, "Root 管理器", "", v -> openRootManager());
        body.addView(manageCard, spaced(0, 0, 0, 16));
    }

    private View setupFixedHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(background);
        header.setPadding(dp(20), dp(12), dp(20), dp(12));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("AK工具箱", 24, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titles.addView(title);

        TextView subtitle = text(getDeviceModel() + " · " + getOsVersion(), 12, muted);
        titles.addView(subtitle, spaced(0, 4, 0, 0));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(10), dp(5), dp(12), dp(5));
        badge.setBackground(round(soft, 14, softBorder, dp(1)));

        statusDot = new View(this);
        statusDot.setBackground(round(muted, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.rightMargin = dp(6);
        badge.addView(statusDot, dotParams);

        statusBadgeText = text("检查中", 12, muted);
        statusBadgeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.addView(statusBadgeText);

        header.addView(badge);
        return header;
    }

    private boolean isGoogleTileEnabled() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_GOOGLE_TILE, false);
    }

    private void setGoogleTileEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_GOOGLE_TILE, enabled).apply();
    }

    @Override protected void onResume() {
        super.onResume();
        if (hideLauncher != null) {
            updatingLauncher = true;
            hideLauncher.setChecked(isLauncherHidden());
            updatingLauncher = false;
        }
        updateScreenTimeCount();
        checkRootAccess();
        if (!restarting) BridgeClient.send(this, BridgeClient.QUERY, null, this::showResult);
    }

    private void updateScreenTimeCount() {
        if (screenTimeCountText == null) return;
        try {
            int count = ScreenTimeFilterProvider.selected(this).size();
            screenTimeCountText.setText(count > 0 ? count + " 个应用" : "");
        } catch (Throwable ignored) {
            screenTimeCountText.setText("");
        }
    }

    private ComponentName launcherComponent() {
        return new ComponentName(this, getPackageName() + ".LauncherActivity");
    }

    private boolean isLauncherHidden() {
        return getPackageManager().getComponentEnabledSetting(launcherComponent())
            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void checkRootAccess() {
        int request = ++rootCheckSequence;
        rootReady = false;
        ROOT_CHECK.execute(() -> {
            RootAccessCheck.Result result = RootAccessCheck.check(new String[] { "su", "-c", "id -u" }, 4000);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != rootCheckSequence) return;
                rootReady = result == RootAccessCheck.Result.GRANTED;
                if (result == RootAccessCheck.Result.NOT_GRANTED && !rootNoticeOpen) showRootReminder();
                if (rootReady && !legacyRestoreAttempted && BackgroundPolicy.hasPendingRestore(this)) {
                    legacyRestoreAttempted = true;
                    BackgroundPolicy.change(this, false, res -> {});
                }
            });
        });
    }

    private void option(LinearLayout group, String key, String label) {
        Switch control = switchRow(group, label, Options.enabled(this, key), (v, checked) -> {
            if (rendering || !connected) return;
            BridgeClient.send(this, BridgeClient.SET_OPTION,
                new Intent().putExtra("key", key).putExtra("value", checked), this::showResult);
        });
        options.put(key, control);
        control.setEnabled(false);
    }

    private void showResult(Intent result) {
        if (isFinishing() || isDestroyed()) return;
        connected = result.getBooleanExtra("connected", false);
        if (connected) {
            statusDot.setBackground(round(greenDot, 4));
            statusBadgeText.setText("已激活");
            statusBadgeText.setTextColor(greenDot);
        } else {
            statusDot.setBackground(round(muted, 4));
            statusBadgeText.setText("未连接");
            statusBadgeText.setTextColor(muted);
        }

        rendering = true;
        if (googleTileSwitch != null) {
            googleTileSwitch.setEnabled(connected && !restarting);
        }

        for (Map.Entry<String, Switch> entry : options.entrySet()) {
            entry.getValue().setEnabled(connected && !restarting && !(paymentBusy && Options.REAR_PAYMENT.equals(entry.getKey())));
            if (connected) entry.getValue().setChecked(result.getBooleanExtra(entry.getKey(), !Options.REAR_PAYMENT.equals(entry.getKey())));
        }
        rendering = false;

        String error = result.getStringExtra("error");
        if (error != null) toast(error);
    }

    private void restartSystemUi() {
        if (!connected || busy || restarting) {
            toast("模块未连接，无法重启系统界面");
            return;
        }
        restarting = true;
        if (googleTileSwitch != null) googleTileSwitch.setEnabled(false);
        for (Switch option : options.values()) option.setEnabled(false);
        toast("正在重启系统界面");
        BridgeClient.send(this, BridgeClient.RESTART_SYSTEM_UI, null, result -> {
            if (!result.getBooleanExtra("restarting", false) || result.hasExtra("error")) {
                restarting = false;
                showResult(result);
                return;
            }
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
                toast(error != null ? error : "模块未连接，无法添加 Google 服务磁贴");
                if (googleTileSwitch != null) {
                    rendering = true;
                    googleTileSwitch.setChecked(false);
                    setGoogleTileEnabled(false);
                    rendering = false;
                }
            } else {
                toast("已添加 Google 服务磁贴至控制中心");
            }
        });
    }

    private void openManager() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (intent == null) toast("请从 LSPosed 通知或桌面快捷方式打开管理器");
        else startActivity(intent);
    }

    private void showRootReminder() {
        rootNoticeOpen = true;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(22), dp(22), dp(18));
        panel.setBackground(round(surface, 20));

        TextView title = text("请授予 Root 权限", 19, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        panel.addView(title, spaced(0, 0, 0, 8));

        TextView detail = text("部分高级操作需要 Root 授权。请在 KernelSU 或其他 Root 管理器中为 AK工具箱开启超级用户权限。", 13, muted);
        detail.setLineSpacing(dp(2), 1f);
        panel.addView(detail, spaced(0, 0, 0, 16));

        Button open = rootDialogButton("打开 Root 管理器", true);
        Button authorized = rootDialogButton("我已授权", false);
        Button later = rootDialogButton("稍后", false);
        panel.addView(open, spaced(0, 0, 0, 8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = dp(4);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.leftMargin = dp(4);
        actions.addView(authorized, left);
        actions.addView(later, right);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        open.setOnClickListener(v -> {
            openRootManager();
            dialog.dismiss();
        });
        authorized.setOnClickListener(v -> { dialog.dismiss(); checkRootAccess(); });
        later.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> rootNoticeOpen = false);
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() == null) return;
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(380));
            dialog.getWindow().setLayout(width, -2);
        });
        dialog.show();
    }

    private Button rootDialogButton(String label, boolean primary) {
        Button control = new Button(this);
        control.setText(label);
        control.setTextSize(14);
        control.setAllCaps(false);
        control.setMinHeight(dp(44));
        control.setTextColor(primary ? Color.WHITE : accent);
        control.setBackground(round(primary ? accent : soft, 12));
        control.setStateListAnimator(null);
        control.setElevation(0);
        return control;
    }

    private boolean openRootManager() {
        for (String pkg : new String[] { "me.weishu.kernelsu", "com.topjohnwu.magisk", "me.bmax.apatch" }) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) continue;
            try { startActivity(intent); return true; }
            catch (RuntimeException ignored) { }
        }
        toast("未找到常见 Root 管理器");
        return false;
    }

    private void section(String title) {
        TextView heading = text(title, 13, muted);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        body.addView(heading, spaced(4, 4, 4, 8));
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(round(surface, 16, cardBorder, dp(1)));
        return view;
    }

    private Switch switchRow(LinearLayout group, String title, boolean checked, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(52));

        TextView label = text(title, 15, ink);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        Switch control = new Switch(this);
        control.setChecked(checked);
        control.setShowText(false);
        if (listener != null) control.setOnCheckedChangeListener(listener);
        row.addView(control);

        row.setOnClickListener(v -> { if (control.isEnabled()) control.setChecked(!control.isChecked()); });
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    private TextView arrowRow(LinearLayout group, String title, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(52));

        TextView label = text(title, 15, ink);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        TextView valueView = text(value, 13, muted);
        valueView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));

        TextView arrow = text("›", 18, muted);
        arrow.setPadding(dp(6), 0, 0, dp(1));
        row.addView(arrow, new LinearLayout.LayoutParams(-2, -2));

        if (onClick != null) row.setOnClickListener(onClick);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return valueView;
    }

    private void addDivider(LinearLayout group) {
        View line = new View(this);
        line.setBackgroundColor(divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        group.addView(line, params);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        return round(color, radius, 0, 0);
    }

    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (strokeWidth > 0 && strokeColor != 0) {
            shape.setStroke(strokeWidth, strokeColor);
        }
        return shape;
    }

    private LinearLayout.LayoutParams spaced(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

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
