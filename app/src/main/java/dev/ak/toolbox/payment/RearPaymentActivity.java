package dev.ak.toolbox.payment;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/** A secure, non-interactive surface on the rear display. */
public final class RearPaymentActivity extends Activity {
    private View content;
    private boolean closePending;
    private final Runnable closeIfEmpty = () -> {
        closePending = false;
        if (!PaymentSession.fresh() && !isFinishing()) { Log.i("AKRearPayment", "closing empty rear window"); finish(); }
    };
    private final Runnable closeIfOnMain = () -> {
        if (getDisplay() == null || getDisplay().getDisplayId() != 1) finish();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        content = new View(this) {
            final PaymentRenderer renderer = new PaymentRenderer();
            boolean geometryReported;
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (!PaymentSession.fresh()) return;
                Bitmap qr = PaymentSession.qr(), barcode = PaymentSession.barcode();
                if (qr == null || barcode == null || qr.isRecycled() || barcode.isRecycled()) return;
                WindowInsets insets = getRootWindowInsets();
                DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
                Rect display = getWindowManager().getMaximumWindowMetrics().getBounds();
                int[] origin = new int[2]; getLocationOnScreen(origin);
                int left = Math.max(0, display.left + (cutout == null ? 0 : cutout.getSafeInsetLeft()) - origin[0]);
                int right = Math.min(getWidth(), display.right - (cutout == null ? 0 : cutout.getSafeInsetRight()) - origin[0]);
                int top = Math.max(0, display.top + (cutout == null ? 0 : cutout.getSafeInsetTop()) - origin[1]);
                int bottom = Math.min(getHeight(), display.bottom - (cutout == null ? 0 : cutout.getSafeInsetBottom()) - origin[1]);
                int width = right - left, height = bottom - top;
                if (!geometryReported) { geometryReported = true; Log.i("AKRearPayment", "rear safe area=" + width + "x" + height); }
                if (width < 120 || height < 160) return;
                renderer.draw(canvas, left, top, right, bottom, qr, barcode, PaymentSession.label());
            }
        };
        setContentView(content);
        PaymentSession.attach(this);
        Log.i("AKRearPayment", "rear activity created display=" + (getDisplay() == null ? -1 : getDisplay().getDisplayId()));
        content.postDelayed(closeIfOnMain, 3000);
    }

    void frameChanged() {
        if (content == null || isFinishing()) return;
        boolean rear = getDisplay() != null && getDisplay().getDisplayId() == 1;
        WindowManager.LayoutParams params = getWindow().getAttributes();
        float alpha = rear && PaymentSession.fresh() ? 1f : 0f;
        if (params.alpha != alpha) { params.alpha = alpha; getWindow().setAttributes(params); }
        content.invalidate();
        if (PaymentSession.fresh()) {
            content.removeCallbacks(closeIfEmpty); closePending = false;
        } else if (!closePending) {
            closePending = true; content.postDelayed(closeIfEmpty, 1500);
        }
    }

    @Override protected void onDestroy() {
        Log.i("AKRearPayment", "rear activity destroyed");
        if (content != null) { content.removeCallbacks(closeIfEmpty); content.removeCallbacks(closeIfOnMain); }
        PaymentSession.detach(this);
        super.onDestroy();
    }
    @Override protected void onStop() { Log.i("AKRearPayment", "rear activity stopped"); super.onStop(); }
}
