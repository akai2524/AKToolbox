package dev.ak.toolbox;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScreenTimeFilterActivity extends Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<App> all = new ArrayList<>(), visible = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(80);
    private Set<String> selected;
    private int background, surface, ink, muted, accent, soft, divider, cardBorder, softBorder;
    private boolean showSystem;
    private String search = "";
    private TextView count;
    private TextView empty;
    private AppAdapter adapter;

    private record App(String packageName, String label, boolean system) {}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        selected = ScreenTimeFilterProvider.selected(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(16) + bars.left, bars.top + dp(12), dp(16) + bars.right, bars.bottom + dp(16));
            return insets;
        });

        // 顶部导航栏（包含绝对居中的矢量返回键与自解释统计）
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);

        BackButton backBtn = new BackButton(this);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        backParams.gravity = Gravity.CENTER_VERTICAL;
        backParams.rightMargin = dp(12);
        backBtn.setOnClickListener(v -> finish());
        navBar.addView(backBtn, backParams);

        TextView title = text("屏幕时间隐藏", 22, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.gravity = Gravity.CENTER_VERTICAL;
        navBar.addView(title, titleParams);

        count = text("正在读取…", 12, muted);
        count.setIncludeFontPadding(false);
        navBar.addView(count);
        root.addView(navBar, margin(0, 0, 0, 16));

        // 极简搜索栏（44dp 高度，12dp 圆角，1px 发丝边框）
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(ink);
        query.setHintTextColor(muted);
        query.setHint("搜索应用或包名");
        query.setTextSize(14);
        query.setPadding(dp(16), 0, dp(16), 0);
        query.setBackground(round(surface, 12, cardBorder, dp(1)));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                search = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        root.addView(query, new LinearLayout.LayoutParams(-1, dp(44)));

        // 控制选项卡片（砍除全部多余副标文字）
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setBackground(round(surface, 16, cardBorder, dp(1)));
        Switch enabled = switchRow(settings, "启用隐藏过滤", ScreenTimeFilterProvider.preferences(this)
            .getBoolean(ScreenTimeFilterProvider.ENABLED, false));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (!ScreenTimeFilterProvider.preferences(this).edit()
                    .putBoolean(ScreenTimeFilterProvider.ENABLED, checked).commit()) {
                button.setOnCheckedChangeListener(null);
                button.setChecked(!checked);
                toast("保存失败，请重试");
                recreate();
            }
        });

        View switchDiv = new View(this);
        switchDiv.setBackgroundColor(divider);
        LinearLayout.LayoutParams sdivParams = new LinearLayout.LayoutParams(-1, dp(1));
        sdivParams.leftMargin = dp(16);
        sdivParams.rightMargin = dp(16);
        settings.addView(switchDiv, sdivParams);

        Switch system = switchRow(settings, "包含系统应用", false);
        system.setOnCheckedChangeListener((button, checked) -> { showSystem = checked; refresh(); });
        root.addView(settings, margin(0, 16, 0, 16));

        TextView listTitle = text("应用列表", 13, muted);
        listTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(listTitle, margin(4, 0, 4, 8));

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(divider));
        list.setDividerHeight(dp(1));
        list.setClipToPadding(false);
        list.setBackground(round(surface, 16, cardBorder, dp(1)));
        list.setClipToOutline(true);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        empty = text("正在读取应用列表…", 14, muted);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(round(surface, 16, cardBorder, dp(1)));
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        loadApps();
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = text("正在读取应用列表…", 14, muted);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(round(surface, 20));
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        loadApps();
    }

    private void loadApps() {
        loader.execute(() -> {
            PackageManager pm = getPackageManager();
            List<App> found = new ArrayList<>();
            try {
                for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))) {
                    CharSequence label = pm.getApplicationLabel(info);
                    boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    found.add(new App(info.packageName, label == null ? info.packageName : label.toString(), system));
                }
                found.sort(Comparator.comparing(a -> a.label().toLowerCase(Locale.ROOT)));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    all.clear(); all.addAll(found);
                    empty.setText("没有符合条件的应用");
                    refresh();
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    count.setText("读取失败");
                    empty.setText("无法读取应用列表，请返回后重试");
                });
            }
        });
    }

    private void refresh() {
        if (adapter == null) return;
        visible.clear();
        for (App app : all) {
            if (!showSystem && app.system()) continue;
            if (!search.isEmpty() && !app.label().toLowerCase(Locale.ROOT).contains(search)
                    && !app.packageName().toLowerCase(Locale.ROOT).contains(search)) continue;
            visible.add(app);
        }
        visible.sort(Comparator.comparing(app -> !selected.contains(app.packageName())));
        count.setText("已选 " + selected.size() + " · 显示 " + visible.size());
        adapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public App getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            App app = getItem(position);
            LinearLayout row = new LinearLayout(ScreenTimeFilterActivity.this);
            row.setPadding(dp(14), dp(9), dp(12), dp(9));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(68));
            boolean isSelected = selected.contains(app.packageName());
            row.setBackground(round(isSelected ? soft : surface, 14));
            ImageView icon = new ImageView(ScreenTimeFilterActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable appIcon = icons.get(app.packageName());
            if (appIcon == null) {
                try { appIcon = getPackageManager().getApplicationIcon(app.packageName()); }
                catch (PackageManager.NameNotFoundException ignored) { appIcon = getPackageManager().getDefaultActivityIcon(); }
                icons.put(app.packageName(), appIcon);
            }
            icon.setImageDrawable(appIcon);
            LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(36), dp(36));
            iconLayout.rightMargin = dp(14);
            row.addView(icon, iconLayout);
            CheckBox check = new CheckBox(ScreenTimeFilterActivity.this);
            check.setChecked(isSelected);
            check.setButtonTintList(ColorStateList.valueOf(accent));
            check.setContentDescription("隐藏 " + app.label());
            LinearLayout labels = new LinearLayout(ScreenTimeFilterActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView appName = text(app.label(), 15, ink);
            appName.setSingleLine(true);
            appName.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(appName);
            TextView appPackage = text(app.packageName(), 12, muted);
            appPackage.setSingleLine(true);
            appPackage.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            labels.addView(appPackage);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(check);
            check.setOnCheckedChangeListener((button, checked) -> {
                Set<String> next = ScreenTimeFilterProvider.selected(ScreenTimeFilterActivity.this);
                if (checked) next.add(app.packageName()); else next.remove(app.packageName());
                if (ScreenTimeFilterProvider.preferences(ScreenTimeFilterActivity.this).edit()
                        .putStringSet(ScreenTimeFilterProvider.PACKAGES, next).commit()) {
                    selected = next;
                    refresh();
                } else {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(!checked);
                    toast("保存失败，请重试");
                }
            });
            row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            return row;
        }
    }

    private Switch switchRow(LinearLayout parent, String label, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(52));

        TextView name = text(label, 15, ink);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        Switch control = new Switch(this);
        control.setContentDescription(label);
        control.setChecked(checked);
        control.setShowText(false);
        row.addView(control);

        row.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value); result.setTextSize(size); result.setTextColor(color); return result;
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
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2);
        result.setMargins(dp(l), dp(t), dp(r), dp(b)); return result;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { loader.shutdownNow(); super.onDestroy(); }

    private final class BackButton extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        BackButton(Activity context) {
            super(context);
            bgPaint.setColor(soft);
            bgPaint.setStyle(Paint.Style.FILL);
            arrowPaint.setColor(accent);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeWidth(dp(2.2f));
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Math.min(w, h) / 2f;
            canvas.drawCircle(w / 2f, h / 2f, r, bgPaint);

            float cx = w / 2f;
            float cy = h / 2f;
            float arrowW = dp(4f);
            float arrowH = dp(6.5f);
            path.reset();
            path.moveTo(cx + arrowW * 0.5f, cy - arrowH);
            path.lineTo(cx - arrowW * 0.5f, cy);
            path.lineTo(cx + arrowW * 0.5f, cy + arrowH);
            canvas.drawPath(path, arrowPaint);
        }
    }
}
