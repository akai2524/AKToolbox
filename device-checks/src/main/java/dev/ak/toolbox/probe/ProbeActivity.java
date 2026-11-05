package dev.ak.toolbox.probe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Separate, removable device test app. Never included in the module APK. */
public final class ProbeActivity extends Activity {
    private static final String CHANNEL = "ak_device_checks";
    private static final String TAG = "ak-device-check";
    private TextView result;
    private EditText keyboardInput;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(40, 40, 40, 40);
        scroll.addView(body);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(40 + bars.left, 40 + bars.top, 40 + bars.right, 40 + bars.bottom);
            return insets;
        });
        TextView title = label("AK工具箱实机测试", 24);
        body.addView(title);
        if ("ime".equals(getIntent().getStringExtra("check"))) {
            keyboardInput = new EditText(this);
            keyboardInput.setSingleLine(true);
            keyboardInput.setHint("输入法测试框");
            keyboardInput.setContentDescription("输入法测试框");
            keyboardInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            body.addView(keyboardInput, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(label("此页仅含测试文字，用于普通截图、长截图和通知数量验证。", 16));
        result = label("等待检查", 16);
        body.addView(result);
        addButton(body, "检查刚刚复制的截图", this::checkClipboard);
        addButton(body, "发送 24 条测试通知", this::postNotifications);
        addButton(body, "清除本测试应用的通知", this::clearNotifications);
        for (int i = 1; i <= 60; i++) {
            TextView row = label(String.format(java.util.Locale.ROOT, "测试内容 %02d  普通截图与长截图", i), 18);
            row.setMinHeight(120);
            row.setBackgroundColor(i % 2 == 0 ? 0xffeeeeee : 0xfffafafa);
            body.addView(row);
        }
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        main.removeCallbacksAndMessages(null);
        if ("post".equals(intent.getStringExtra("check"))) postNotifications();
        if ("clear".equals(intent.getStringExtra("check"))) clearNotifications();
        if ("ime".equals(intent.getStringExtra("check")) && keyboardInput != null) {
            keyboardInput.requestFocus();
            keyboardInput.postDelayed(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT), 500);
        }
    }

    private void checkClipboard() {
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipDescription description = clipboard.getPrimaryClipDescription();
            if (description == null || !description.hasMimeType("image/*")) {
                report("失败：当前剪贴板不是图片"); return;
            }
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getUri() == null) {
                report("失败：图片 URI 缺失"); return;
            }
            // Invoked only after a screenshot of this test page; do not log URI or other clip content.
            File copy = new File(getCacheDir(), "clipboard-image.png");
            try (InputStream in = getContentResolver().openInputStream(clip.getItemAt(0).getUri());
                 FileOutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IllegalStateException("图片无法打开");
                in.transferTo(out);
            }
            Bitmap bitmap = BitmapFactory.decodeFile(copy.getAbsolutePath());
            if (bitmap == null) throw new IllegalStateException("图片解码失败");
            String mime = getContentResolver().getType(clip.getItemAt(0).getUri());
            String value = "通过：" + mime + "，" + bitmap.getWidth() + "×" + bitmap.getHeight()
                + "，图片 URI 可读，完整解码成功";
            bitmap.recycle();
            report(value);
        } catch (Exception error) { report("失败：" + error.getClass().getSimpleName() + " " + error.getMessage()); }
    }

    private void postNotifications() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            report("测试应用尚未获得通知权限"); return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "工具箱数量测试", NotificationManager.IMPORTANCE_LOW));
        report("正在发送 24 条测试通知");
        for (int i = 1; i <= 24; i++) {
            final int number = i;
            Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(String.format(java.util.Locale.ROOT, "工具箱测试通知 %02d", i))
                .setContentText("仅用于验证同一应用超过 17 条通知")
                .setGroup("ak-device-checks").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true).build();
            main.postDelayed(() -> manager.notify(TAG, number, notification), (i - 1) * 180L);
        }
        main.postDelayed(() -> report("已发送 24 条，应用活动通知数：" + manager.getActiveNotifications().length), 4800);
    }

    private void clearNotifications() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.cancelAll(); // This API only removes this test package's notifications.
        main.postDelayed(() -> report("本测试应用的通知已清除，剩余：" + manager.getActiveNotifications().length), 500);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void report(String value) {
        result.setText(value);
        try (FileOutputStream file = openFileOutput("result.txt", MODE_PRIVATE)) {
            file.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.BLACK);
        view.setTextSize(size);
        view.setPadding(16, 20, 16, 20);
        return view;
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }
}
