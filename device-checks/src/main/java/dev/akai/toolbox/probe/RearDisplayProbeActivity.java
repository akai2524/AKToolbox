package dev.akai.toolbox.probe;

import android.app.Activity;
import android.app.Presentation;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Standalone, non-payment probe for display 1. No screen content is captured. */
public final class RearDisplayProbeActivity extends Activity {
    private static final String TAG = "AkaiRearProbe";
    private static final long DURATION_MS = 60000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Presentation presentation;
    private TextView rearText;
    private TextView status;
    private long started;
    private boolean directRear;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!directRear && (presentation == null || !presentation.isShowing())) return;
            long elapsed = SystemClock.elapsedRealtime() - started;
            if (elapsed >= DURATION_MS) {
                if (directRear) {
                    Log.i(TAG, "direct rear test finished");
                    finish();
                    return;
                }
                closePresentation("测试结束");
                return;
            }
            rearText.setText("背屏测试\n" + (elapsed / 1000) + " 秒\n仅为测试文字");
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getDisplay() != null && getDisplay().getDisplayId() == 1) {
            directRear = true;
            setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            rearText = new TextView(this);
            rearText.setGravity(Gravity.CENTER);
            rearText.setBackgroundColor(Color.WHITE);
            rearText.setTextColor(Color.BLACK);
            rearText.setTextSize(28);
            setContentView(rearText);
            started = SystemClock.elapsedRealtime();
            Log.i(TAG, "direct rear activity started on display 1");
            main.post(refresh);
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 80, 32, 32);
        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);
        Button retry = new Button(this);
        retry.setText("重新开始背屏测试");
        retry.setOnClickListener(view -> restartProbe());
        root.addView(retry);
        setContentView(root);
        showProbe();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        restartProbe();
    }

    private void restartProbe() {
        main.removeCallbacks(refresh);
        if (directRear) {
            started = SystemClock.elapsedRealtime();
            main.post(refresh);
            return;
        }
        Presentation old = presentation;
        presentation = null;
        rearText = null;
        if (old != null) old.dismiss();
        showProbe();
    }

    private void showProbe() {
        DisplayManager manager = getSystemService(DisplayManager.class);
        Display rear = manager == null ? null : manager.getDisplay(1);
        if (rear == null || !rear.isValid()) {
            report("未找到可用的 display 1");
            return;
        }
        try {
            presentation = new Presentation(this, rear);
            rearText = new TextView(presentation.getContext());
            rearText.setGravity(Gravity.CENTER);
            rearText.setBackgroundColor(Color.WHITE);
            rearText.setTextColor(Color.BLACK);
            rearText.setTextSize(28);
            rearText.setText("背屏测试\n0 秒\n仅为测试文字");
            presentation.setContentView(rearText);
            presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            presentation.setOnDismissListener(dialog -> {
                if (presentation != dialog) return;
                main.removeCallbacks(refresh);
                presentation = null;
                rearText = null;
            });
            presentation.show();
            started = SystemClock.elapsedRealtime();
            report("背屏窗口已请求显示。观察背屏是否出现递增秒数，60 秒后自动结束。");
            main.post(refresh);
        } catch (RuntimeException error) {
            presentation = null;
            rearText = null;
            report("背屏窗口创建失败：" + error.getClass().getSimpleName());
        }
    }

    private void closePresentation(String result) {
        main.removeCallbacks(refresh);
        if (presentation != null) presentation.dismiss();
        report(result);
    }

    private void report(String message) {
        if (status != null) status.setText(message);
        Log.i(TAG, message);
    }

    @Override protected void onStop() {
        closePresentation("页面已退出，背屏测试结束");
        super.onStop();
    }
}
