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
    private int background, surface, ink, muted, accent, soft;
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
        background = Color.parseColor(dark ? "#111318" : "#F5F6F8");
        surface = Color.parseColor(dark ? "#1C1F25" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#F1F3F8" : "#17191E");
        muted = Color.parseColor(dark ? "#A4ACBA" : "#717785");
        accent = Color.parseColor(dark ? "#91ADFF" : "#3868E8");
        soft = Color.parseColor(dark ? "#26314C" : "#EAF0FF");
        selected = ScreenTimeFilterProvider.selected(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom);
            return insets;
        });
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);

        BackButton backBtn = new BackButton(this);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        backParams.gravity = Gravity.CENTER_VERTICAL;
        backParams.rightMargin = dp(12);
        backBtn.setOnClickListener(v -> finish());
        navBar.addView(backBtn, backParams);

        TextView title = text("屏幕时间隐藏", 26, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.gravity = Gravity.CENTER_VERTICAL;
        navBar.addView(title, titleParams);

        root.addView(navBar, margin(0, 0, 0, 6));
        TextView help = text("选择不在小米屏幕时间中显示的应用。系统仍会记录使用时间。", 13, muted);
        help.setLineSpacing(dp(3), 1f);
        root.addView(help, margin(0, 0, 0, 12));

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setBackground(round(surface, 20));
        Switch enabled = switchRow(settings, "启用隐藏", "对已选择的应用生效", ScreenTimeFilterProvider.preferences(this)
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
        Switch system = switchRow(settings, "显示系统应用", "关闭时仅显示非系统应用", false);
        system.setOnCheckedChangeListener((button, checked) -> { showSystem = checked; refresh(); });
        root.addView(settings, margin(0, 0, 0, 10));

        TextView scopeHint = text("请在 LSPosed 勾选“小米设置”。修改后强行停止小米设置或重启手机。", 12, muted);
        scopeHint.setLineSpacing(dp(2), 1f);
        root.addView(scopeHint, margin(3, 0, 3, 10));

        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(ink);
        query.setHintTextColor(muted);
        query.setHint("搜索应用名称或包名");
        query.setTextSize(14);
        query.setPadding(dp(14), 0, dp(14), 0);
        query.setBackground(round(surface, 14));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                search = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        root.addView(query, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout listHeading = new LinearLayout(this);
        listHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView listTitle = text("应用列表", 15, ink);
        listTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        listHeading.addView(listTitle, new LinearLayout.LayoutParams(0, -2, 1));
        count = text("正在读取…", 12, muted);
        listHeading.addView(count);
        root.addView(listHeading, margin(4, 12, 4, 7));
        TextView sortHint = text("已选应用自动置顶", 11, muted);
        root.addView(sortHint, margin(4, 0, 4, 7));
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(6));
        list.setPadding(dp(10), dp(10), dp(10), dp(10));
        list.setClipToPadding(false);
        list.setBackground(round(surface, 20));
        list.setClipToOutline(true);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
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
            LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconLayout.rightMargin = dp(12);
            row.addView(icon, iconLayout);
            CheckBox check = new CheckBox(ScreenTimeFilterActivity.this);
            check.setChecked(isSelected);
            check.setContentDescription("隐藏 " + app.label());
            LinearLayout labels = new LinearLayout(ScreenTimeFilterActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView appName = text(app.label(), 15, ink);
            appName.setSingleLine(true);
            appName.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(appName);
            TextView appPackage = text(app.packageName(), 11, muted);
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

    private Switch switchRow(LinearLayout parent, String label, String description, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 15, ink));
        labels.addView(text(description, 12, muted));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setContentDescription(label);
        control.setChecked(checked);
        row.addView(control);
        row.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        parent.addView(row);
        return control;
    }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value); result.setTextSize(size); result.setTextColor(color); return result;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
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
